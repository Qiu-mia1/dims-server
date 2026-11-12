package com.isim.dims.schedule;

import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.BosObjectSummary;
import com.baidubce.services.bos.model.GetObjectRequest;
import com.baidubce.services.bos.model.ListObjectsRequest;
import com.baidubce.services.bos.model.ListObjectsResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.DirectoryImportRequest;
import com.isim.dims.service.FisheyePreviewService;
import com.isim.dims.service.LocalDirectoryImportService;
import com.isim.dims.service.LogRecordService;
import com.isim.dims.service.impl.StorageAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务：从百度 BOS 桶周期下载指定前缀下的对象文件。
 *
 * <p>每 3 分钟执行一次（间隔可通过 {@code dims.download.interval-ms} 调整）。采用增量式拉取：
 * 每次扫描桶内对象，只处理「最后修改时间晚于记录文件中最后一次上传完成时间」的对象，
 * 首次运行（无历史记录）则全量处理。</p>
 *
 * <p>下载完成后把对象信息（object key / 本地路径 / 大小 / 最后修改时间 / 下载时间）追加写入
 * JSON 记录文件（默认 {@code {local-dir}/download-record.json}），最新记录排在末尾；
 * 同一对象已记录过（且最后修改时间一致）则跳过，实现幂等去重。</p>
 *
 * <p><b>本类在整条自动接入链路中的位置</b>（详见 docs/DIMS-数据来源与代码映射.md）：</p>
 * <pre>
 *   BOS 桶 mmt_ads/{bosPrefix}/{yyyyMMdd}/{md5}/{session}/public/...
 *     → 本类增量下载到 {local-dir}
 *     → LocalDirectoryImportService（解析 meta、反查 VIN、生成鱼眼预览）
 *     → 建立 META_READY 切片（V29「元数据先行、按需加载」）
 *     → 用户点「加载」后由 SliceLoadService 拉全量，再走 IngestionPipelineServiceImpl
 * </pre>
 * <p>同目录的 *_forever_log_* / *_bsw_log_* 日志包由 LogRecordService 旁路登记到
 * {@code log_record}，不下载日志本体。多副本防重入依赖 Redisson 锁 + 记录文件，
 * 排查「重复切片 / 定时任务不生效」时优先看这两处与 {@code @Scheduled} 是否被注释。</p>
 *
 * <p>注意：类注释写的「每 3 分钟」是早期配置（{@code dims.download.interval-ms}），
 * 当前实际生效的是方法上的 {@code @Scheduled(cron = "0 0/1 * * * ? ")}（每分钟）。</p>
 *
 * @feature: ingestion
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
//@ConditionalOnProperty(name = "dims.storage.type", havingValue = "bos")
public class DownloadMMTDataBOSSchedule {

    /**
     * 幂等判定时最后修改时间允许的误差（秒），避免 SDK 时间精度抖动导致重复下载
     */
    private static final long LAST_MODIFIED_TOLERANCE_SECONDS = 60;

    private final ObjectMapper objectMapper;

    private final RedissonClient redissonClient;

    private final StorageAccess storageAccess;

    @Autowired
    private LocalDirectoryImportService localDirectoryImportService;

    /**
     * 日志包登记服务：meta-only 阶段把 `_log` 日志文件元数据写入 log_record，
     * 供「日志浏览」页面展示与整目录打包下载（日志文件本体不下载）。
     */
    @Autowired
    private LogRecordService logRecordService;

    /**
     * 防重入标志：downloadDataZytSource 正在执行时为 true，
     * 下一次调度触发时直接跳过，避免并发扫描/下载/导入。
     * （非 final：避免被 @RequiredArgsConstructor 纳入构造器注入）
     */
    private AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 防重入标志：importDataByDay（按天导入）执行时为 true。
     * 与 downloadDataZytSource / downloadDataBySubdir 所用标志相互独立，可并行不互相抢占。
     */
    private AtomicBoolean runningImportByDay = new AtomicBoolean(false);

    @Value("${minio.download-bucket: mmt_ads}")
    private String bucket;

    //    @Value("${dims.download.bos-prefix: mmt-ads/standard/production_car/source/jiangling-MMT-CX835-ICA}")
    @Value("${dims.download.bos-prefix: mmt_test}")
    private String bosPrefix;

    // momenta MXF 数据前缀：bucket 下 momentaMXFPrefix 内的文件，子目录为每天目录（yyyyMMdd），
    // 再下一级为文件；为空时 downloadMomentaMXFByDay 调度直接跳过
    @Value("${dims.download.bos-Momenta-MXF-prefix: Momenta-MXF/LUS_CX835ICA_dev}")
    private String momentaMXFPrefix;

    @Value("${dims.download.local-dir: /data/downloads}")
    private String localDir;

    @Value("${dims.download.record-file:}")
    private String recordFile;

    @Value("${dims.download.subdir-record-file:}")
    private String subdirRecordFile;

    // 按天导入的历史起始日期（含），默认 2026-09-01，格式 yyyy-MM-dd
    @Value("${dims.download.history-start-date:2026-09-20}")
    private String historyStartDate;

    @Value("${dims.download.history-end-date:2026-09-28}")
    private String historyEndDate;

    @Value("${dims.download.history-flag:true}")
    private String historyFlag;

    // 兼容保留：历史窗口配置，已由「记录最后上传时间做增量」取代，不再参与过滤
    @Value("${dims.download.window-minutes: 3}")
    private long windowMinutes;

    // 上传稳定性窗口：只处理上传完成时间距今超过该分钟数的对象（<=0 表示不限制）
    @Value("${dims.download.upload-lag-minutes: 30}")
    private long uploadLagMinutes;

    // 近 N 小时（可配置）窗口：downloadDataBySubdir 不仅处理今天目录，还处理近 recentHours 小时
    // 内所涉及的所有历史日目录（默认 48 小时 → 今天 + 昨天），避免延迟上传落到旧日目录的数据被漏掉；
    // <=0 时仅处理今天，保持原语义
    @Value("${dims.download.recent-hours:48}")
    private long recentHours;

    // 子集目录导入成功后是否删除本地下载的原始文件（meta-only 模式下包含前视鱼眼 h265）
    @Value("${dims.download.mmt.cleanup-downloaded:true}")
    private boolean cleanupDownloaded;

    // momenta MXF 已下载子目录记录文件（按天 key 幂等去重；默认 {local-dir}/download-momenta-mxf-subdirs.json）
    @Value("${dims.download.momenta-mxf-subdir-record-file:}")
    private String momentaMXFRecordFile;

    @Autowired
    private BosClient bosClient;

    /**
     * 定时下载入口。每 3 分钟（可配置）触发一次。
     */
//    @Scheduled(fixedDelayString = "${dims.download.interval-ms:180000}")
//    @Scheduled(cron = "0 0/1 * * * ? ")
    public void downloadDataZytSource() {
        // 防重入：已有任务正在执行时，本次调度直接跳过（非阻塞）
        if (!running.compareAndSet(false, true)) {
            log.info("上一次下载/导入任务仍在执行，本次调度跳过");
            return;
        }
        try {
            // 记录文件中最后一次上传完成时间；首次运行（无历史）以纪元起点为基准，即全量拉取
//        Instant lastProcessedTime = latestLastModified().orElse(Instant.EPOCH);
            Instant lastProcessedTime =
                    latestLastModified()
                            .orElse(Instant.parse("2026-08-28T00:00:00Z"));
            log.info("扫描 BOS 桶[{}]前缀[{}]，仅处理最后修改时间晚于 {} 的对象",
                    bucket, bosPrefix, lastProcessedTime);

            List<DownloadRecord> downloaded = new ArrayList<>();
            // 保存 bosPrefix 下出现的一级目录（去重）；需声明在分页循环外，避免翻页时被重置
            Set<String> parentDirs = new HashSet<>();
            String marker = null;

            do {
                ListObjectsRequest request = new ListObjectsRequest(bucket);
                request.setMaxKeys(1000);
                if (bosPrefix != null && !bosPrefix.isBlank()) {
                    request.setPrefix(bosPrefix);
                }
                if (marker != null) {
                    request.setMarker(marker);
                }

                ListObjectsResponse response = bosClient.listObjects(request);

                for (BosObjectSummary obj : response.getContents()) {
                    String key = obj.getKey();

                    // 跳过目录占位符（空对象）
                    if (key.endsWith("/") && obj.getSize() == 0) {
                        continue;
                    }

                    // 命名包含forever_log 跳过
                    if (key.contains("forever_log")) {
                        continue;
                    }

                    Instant lastModified = obj.getLastModified() == null
                            ? null
                            : obj.getLastModified().toInstant();
                    // 增量：只处理最后修改时间晚于最后记录时间的对象
                    if (lastModified == null || !lastModified.isAfter(lastProcessedTime)) {
                        continue;
                    }

                    // 已下载过则跳过（幂等去重）
                    if (alreadyDownloaded(key, lastModified)) {
                        log.debug("对象已下载，跳过: {}", key);
                        continue;
                    }

                    try {
                        // 记录该对象所属文件夹全路径（bosPrefix 下 前二个子集目录）
                        String parentDir = resolveParentDir(key);
                        if (parentDir != null) {
                            parentDirs.add(localDir + "/" + parentDir);
                        }

                        Path localFile = resolveLocalPath(key);
                        Files.createDirectories(localFile.getParent());

                        GetObjectRequest getRequest = new GetObjectRequest(bucket, key);
                        bosClient.getObject(getRequest, localFile.toFile());

                        downloaded.add(toRecord(obj, localFile));
                        log.info("下载完成: {} -> {} ({} 字节)", key, localFile, obj.getSize());
                    } catch (Exception e) {
                        log.error("下载对象失败: {}", key, e);
                    }
                }

                for (String parentDir : parentDirs) {
                    try {
                        DirectoryImportRequest directoryImportRequest = new DirectoryImportRequest();
                        directoryImportRequest.setDirectory(parentDir);
                        directoryImportRequest.setSource("MMT");
                        localDirectoryImportService.importDirectory(directoryImportRequest);
                    } catch (Exception e) {
                        log.error("异常：{}", e);
                    }
                }

                marker = response.isTruncated() ? response.getNextMarker() : null;
            } while (marker != null);

            log.info("扫描到 bosPrefix 下第二个子集目录 {} 个: {}", parentDirs.size(), parentDirs);

            if (downloaded.isEmpty()) {
                log.info("本次无晚于最后记录时间的新对象，无新增下载");
                return;
            }
            appendRecords(downloaded);
            log.info("本次下载 {} 个文件，最新记录: {}", downloaded.size(), downloaded.get(downloaded.size() - 1));
        } catch (Exception e) {
            log.error("下载异常：{}", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 防重入标志：downloadDataBySubdir 执行时为 true。
     * 与 downloadDataZytSource 所用 running 相互独立，两个入口可并行不互相抢占。
     */
//    private AtomicBoolean runningSubdir = new AtomicBoolean(false);

    /**
     * 按「子集目录」逐日下载：下载 {@code bosPrefix/(yyyyMMdd 今天)/子集目录} 下所有对象，
     * 每个子集目录（第二级目录，如 {@code bosPrefix/today/session01/}）作为一个完整下载过程。
     *
     * <p>下载完成后把该子集目录 key 记入独立 JSON 记录文件（默认
     * {@code {local-dir}/download-subdirs.json} 或 {@code dims.download.subdir-record-file}），
     * 下次调度遇到已记录的子集目录直接跳过，实现按目录幂等去重。</p>
     *
     * <p>对象结构约定：{@code {bosPrefix}/{yyyy-MM-dd}/{子集目录}/{下一级子目录}/{文件}}
     * （无下一级时即 {@code {bosPrefix}/{yyyy-MM-dd}/{子集目录}/{文件}}）。</p>
     *
     * <p>处理单元已下沉到「下一级子目录」：下载 + 记录均以该目录为单位做增量与幂等去重，
     * 同一 session（一级子目录）下新出现的下一级子目录也能被后续调度继续处理；
     * 导入仍以一级子目录（session 目录）为单位调用 {@code importDirectory}，保持原逻辑。</p>
     *
     * <p>除今天外，还会处理近 {@code recentHours} 小时（默认 24，可配置
     * {@code dims.download.recent-hours}）内所涉及的所有历史日目录：把因上传延迟而落在前几日目录、
     * 尚未处理的对象也一并归并处理，避免跨天/延迟晚到数据被漏掉。</p>
     *
     * <p><b>meta-only 模式</b>：每个子集目录只下载 meta（事件统计元信息）与前视鱼眼 h265，
     * 导入仅建切片（status=META_READY）并异步转码鱼眼预览 mp4；用户在数据浏览器点
     * 「加载」时，后端再按切片记录的 import_bos_key 全量拉取并走完整导入流水线。</p>
     */
    @Scheduled(cron = "0 0/1 * * * ? ")
    public void downloadDataBySubdir() {
        // 防重入：已有子目录下载任务在执行时，本次直接跳过（非阻塞）
//        if (!runningSubdir.compareAndSet(false, true)) {
//            log.info("上一次子目录下载任务仍在执行，本次调度跳过");
//            return;
//        }

        String lockKey = "lock:downloadDataBySubdir";
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(10, 60, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("downloadDataBySubdir 获取锁失败");
                return;
            }

            LocalDate today = LocalDate.now();
            // 处理窗口 = 近 recentHours 小时所覆盖到的各日目录（默认 24 → 今天 + 昨天），
            // 按日期从旧到新排列，保证日志与记录文件顺序稳定
            List<String> processingPrefixes = recentDayPrefixes(today);
            log.info("扫描 BOS 桶[{}]近 {} 小时内的日目录前缀 {} 个: {}",
                    bucket, recentHours, processingPrefixes.size(), processingPrefixes);

            // 1. 扫描处理窗口内各日前缀下所有对象，按子集目录分组（key = 子集目录完整前缀）
            Map<String, Set<BosObjectSummary>> subdirs = new LinkedHashMap<>();
            for (String dayPrefix : processingPrefixes) {
                subdirs.putAll(groupBySubdir(dayPrefix));
            }
            if (subdirs.isEmpty()) {
                log.info("处理前缀下无对象，无下载");
                return;
            }
            log.info("扫描到子集目录 {} 个: {}", subdirs.size(), subdirs.keySet());

            // 2. 读取已下载子集目录记录
            Set<String> downloadedDirs = loadDownloadedSubdirs();
            log.debug("downloadedDirs: {}", downloadedDirs);
            // 清理非处理窗口内的旧记录：仅保留仍处于近 recentHours 窗口内的日目录子集目录记录；
            // 窗口移出后（超过 recentHours 的日目录）自动清除，避免记录文件无限累积
            int beforeClean = downloadedDirs.size();
            if (downloadedDirs.removeIf(d ->
                    processingPrefixes.stream().noneMatch(p -> d.startsWith(p)))) {
                log.info("已清理 {} 条非处理窗口内旧记录，仅保留 {} 条子集目录记录",
                        beforeClean - downloadedDirs.size(), downloadedDirs.size());
                saveDownloadedSubdirs(downloadedDirs);
            }

            // 3. 逐个下载未记录的子集目录（已下沉到下一级子目录粒度），下载成功后记录
            int processed = 0;
            for (Map.Entry<String, Set<BosObjectSummary>> e : subdirs.entrySet()) {
                String subDir = e.getKey();
                if (downloadedDirs.contains(subDir)) {
                    log.info("子集目录已下载，跳过: {}", subDir);
                    continue;
                }
                try {
//                    downloadSubdir(subDir, e.getValue());
//
//                    // 导入仍以「一级子目录（session 目录）」为单位：subDir 已下沉到下一级子目录，
//                    // 这里取其所属的一级子目录保持原有导入粒度
//                    String parentSubDir = resolveParentSubdir(subDir, processingPrefixes);
//                    DirectoryImportRequest directoryImportRequest = new DirectoryImportRequest();
//                    // parentSubDir 已含 bosPrefix（{bosPrefix}/{today}/{folder}），
//                    // 直接拼 localDir，避免 bosPrefix 重复导致目录不存在
//                    directoryImportRequest.setDirectory(localDir + "/" + parentSubDir);
//                    directoryImportRequest.setSource("MMT");
//                    localDirectoryImportService.importDirectory(directoryImportRequest);

                    // meta-only 模式：只拉取用于事件统计的 meta 与前视鱼眼 h265（保持目录结构），
                    // 其余文件待用户在数据浏览器点「加载」时按 slice.import_bos_key 全量拉取
                    downloadSubdirMetaOnly(subDir, e.getValue());

                    // 导入仍以「一级子目录（session 目录）」为单位：subDir 已下沉到下一级子目录，
                    // 这里取其所属的一级子目录保持原有导入粒度
                    String parentSubDir = resolveParentSubdir(subDir, processingPrefixes);
                    DirectoryImportRequest directoryImportRequest = new DirectoryImportRequest();
                    // parentSubDir 已含 bosPrefix（{bosPrefix}/{today}/{folder}），
                    // 直接拼 localDir，避免 bosPrefix 重复导致目录不存在
                    directoryImportRequest.setDirectory(localDir + "/" + parentSubDir);

                    // subDir 已含 bosPrefix（groupBySubdir 返回 {bosPrefix}/{today}/{folder}），
                    directoryImportRequest.setSource("MMT");
                    directoryImportRequest.setMetaOnly(true);
                    directoryImportRequest.setBosKeyPrefix(parentSubDir);
                    // meta-only 导入：仅解析 meta 建切片（META_READY）并异步转码前视鱼眼预览，
                    // 不校验 mfbag、不跑转换流水线
                    localDirectoryImportService.importDirectoryMetaOnly(directoryImportRequest);

//                    if (cleanupDownloaded) {
//                        Path localSubDir = Path.of(localDir).resolve(parentSubDir);
//                        this.storageAccess.deleteRecursively(localSubDir);
//                    }

                } catch (Exception ex) {
                    log.error("子集目录下载失败，本次不记录，下次将重试: {}", subDir, ex);
                    continue;
                }
                // 下载成功后才记录，保证子集目录作为一个完整过程原子落记
                downloadedDirs.add(subDir);
                saveDownloadedSubdirs(downloadedDirs);
                processed++;
                log.info("子集目录下载完成并已记录: {}", subDir);
            }
            log.info("本次新下载子集目录 {} 个", processed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("downloadDataBySubdir 获取分布式锁被中断", e);
        } catch (Exception e) {
            log.error("按子集目录下载异常：{}", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /* ==================== 按天下载 momentaMXF 数据 ==================== */

    /**
     * 按「每天目录」增量下载 bucket 下 {@code momentaMXFPrefix} 前缀文件，逻辑与
     * {@link #downloadDataBySubdir()} 一致：以子目录为处理单元——此处子目录即「每天目录」
     * （{@code {momentaMXFPrefix}/{yyyyMMdd}/}，下一级为文件）。
     *
     * <p>处理近 {@code recentHours} 小时（默认 48）涉及的所有日目录，因此也能覆盖 48 小时
     * 之前（最近几天）延迟上传的数据。某天目录下载+导入成功后，将其 key 记入独立的
     * 「48 小时记录 json 文件」（默认 {@code {local-dir}/download-momenta-mxf-subdirs.json}
     * 或 {@code dims.download.momenta-mxf-subdir-record-file}），下次对已记录的日目录直接跳过，
     * 实现每天增量下载与幂等去重。记录清理仅保留仍在处理窗口内的日目录。</p>
     *
     * <p>需配置 {@code dims.download.momenta-mxf-prefix}；未配置时本次调度跳过。</p>
     */
//    @Scheduled(cron = "0 0/1 * * * ? ")
    public void downloadMomentaMXFByDay() {
        if (momentaMXFPrefix == null || momentaMXFPrefix.isBlank()) {
            log.warn("momentaMXFPrefix 未配置(dims.download.bos-Momenta-MXF-prefix)，本次调度跳过");
            return;
        }

        String lockKey = "lock:downloadMomentaMXFByDay";
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(10, 60, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("downloadMomentaMXFByDay 获取锁失败");
                return;
            }

            LocalDate today = LocalDate.now();
            // 处理窗口 = 近 recentHours 小时所覆盖到的各日目录（默认 48 → 今天 + 昨天），按日期从旧到新排列
            List<String> dayKeys = recentMomentaMXFDayKeys(today);
            log.info("扫描 BOS 桶[{}]近 {} 小时内 momentaMXF 日目录 {} 个: {}",
                    bucket, recentHours, dayKeys.size(), dayKeys);

            // 1. 逐日列出该日前缀下所有对象，组 key = 日目录完整前缀 {prefix}/{yyyyMMdd}
            Map<String, Set<BosObjectSummary>> dirs = new LinkedHashMap<>();
            for (String dayKey : dayKeys) {
                Set<BosObjectSummary> objs = listMomentaMXFDayObjects(dayKey);
                if (!objs.isEmpty()) {
                    dirs.put(dayKey, objs);
                }
            }
            if (dirs.isEmpty()) {
                log.info("momentaMXF 处理窗口内无对象，无下载");
                return;
            }
            log.info("momentaMXF 扫描到日目录 {} 个: {}", dirs.size(), dirs.keySet());

            // 2. 读取已下载日目录记录，仅保留仍处于近 recentHours 窗口内的记录
            Set<String> downloadedDirs = loadMomentaMXFDownloadedDirs();
            log.debug("momentaMXF downloadedDirs: {}", downloadedDirs);
            int beforeClean = downloadedDirs.size();
            if (downloadedDirs.removeIf(d ->
                    dayKeys.stream().noneMatch(p -> d.startsWith(p)))) {
                log.info("已清理 {} 条非窗口内 momentaMXF 旧记录，仅保留 {} 条",
                        beforeClean - downloadedDirs.size(), downloadedDirs.size());
                saveMomentaMXFDownloadedDirs(downloadedDirs);
            }

            // 3. 逐个下载未记录的日目录，下载成功后记录
            int processed = 0;
            for (Map.Entry<String, Set<BosObjectSummary>> e : dirs.entrySet()) {
                String dayKey = e.getKey();
                if (downloadedDirs.contains(dayKey)) {
                    log.info("momentaMXF 日目录已下载，跳过: {}", dayKey);
                    continue;
                }
                try {
                    downloadSubdir(dayKey, e.getValue());

//                    DirectoryImportRequest directoryImportRequest = new DirectoryImportRequest();
//                    // dayKey 已含 momentaMXFPrefix（{prefix}/{yyyyMMdd}），直接拼 localDir
//                    directoryImportRequest.setDirectory(localDir + "/" + dayKey);
//                    directoryImportRequest.setSource("MXF");
//                    localDirectoryImportService.importDirectory(directoryImportRequest);
                } catch (Exception ex) {
                    log.error("momentaMXF 日目录下载失败，本次不记录，下次将重试: {}", dayKey, ex);
                    continue;
                }
                // 下载+导入成功后才记录，保证该日目录作为一个完整过程原子落记
                downloadedDirs.add(dayKey);
                saveMomentaMXFDownloadedDirs(downloadedDirs);
                processed++;
                log.info("momentaMXF 日目录下载完成并已记录: {}", dayKey);
            }
            log.info("本次新下载 momentaMXF 日目录 {} 个", processed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("downloadMomentaMXFByDay 获取分布式锁被中断", e);
        } catch (Exception e) {
            log.error("momentaMXF 按日目录下载异常：{}", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 计算「近 {@code recentHours} 小时」处理窗口内涉及的所有 momentaMXF 日目录 key
     * （{@code {momentaMXFPrefix}/{yyyyMMdd}}，从旧到新排列）。{@code recentHours <= 0} 时仅今天。
     */
    private List<String> recentMomentaMXFDayKeys(LocalDate today) {
        List<String> keys = new ArrayList<>();
        long daysBack = recentHours <= 0 ? 0 : (recentHours + 23) / 24;
        for (long i = daysBack; i >= 0; i--) {
            keys.add(buildMomentaMXFDayKey(today.minusDays(i)));
        }
        return keys;
    }

    /**
     * 组装某一天的 momentaMXF 日目录 key：{@code {momentaMXFPrefix}/{yyyyMMdd}}。
     */
    private String buildMomentaMXFDayKey(LocalDate day) {
        String dayStr = day.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return momentaMXFPrefix + "/" + dayStr;
    }

    /**
     * 分页列出 {@code momentaMXFPrefix} 下某天目录（key={@code {prefix}/{yyyyMMdd}}）内所有对象，
     * 只纳入「上传完成时间距今已超过 {@code uploadLagMinutes} 分钟」的稳定对象，
     * 跳过目录占位符 / {@code forever_log} 对象。
     */
    private Set<BosObjectSummary> listMomentaMXFDayObjects(String dayKey) {
        Set<BosObjectSummary> objects = new LinkedHashSet<>();
        String prefix = dayKey + "/";
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest(bucket);
            request.setMaxKeys(1000);
            request.setPrefix(prefix);
            if (marker != null) {
                request.setMarker(marker);
            }
            ListObjectsResponse response = bosClient.listObjects(request);

            for (BosObjectSummary obj : response.getContents()) {
                String key = obj.getKey();
                // 跳过目录占位符（空对象）
                if (key.endsWith("/")) {
                    continue;
                }
                // 命名包含 forever_log 跳过
                if (key.contains("forever_log")) {
                    continue;
                }
                // 只处理上传完成时间距今已超 uploadLagMinutes 分钟的稳定对象
                if (!isMature(obj)) {
                    log.debug("momentaMXF 对象上传未满 {} 分钟，本次跳过: {}", uploadLagMinutes, key);
                    continue;
                }
                objects.add(obj);
            }
            marker = response.isTruncated() ? response.getNextMarker() : null;
        } while (marker != null);
        return objects;
    }

    /* ==================== momentaMXF 子目录下载记录（独立文件） ==================== */

    /**
     * 读取已下载 momentaMXF 日目录 key 列表。
     */
    private Set<String> loadMomentaMXFDownloadedDirs() {
        Path file = momentaMXFRecordFilePath();
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        try {
            List<String> list = objectMapper.readValue(
                    file.toFile(), new TypeReference<List<String>>() {
                    });
            return new LinkedHashSet<>(list);
        } catch (IOException e) {
            log.warn("读取 momentaMXF 记录失败，按空记录处理: {}", file, e);
            return new LinkedHashSet<>();
        }
    }

    /**
     * 持久化已下载 momentaMXF 日目录 key 列表（升序写入）。
     */
    private void saveMomentaMXFDownloadedDirs(Collection<String> dirs) {
        try {
            Path file = momentaMXFRecordFilePath();
            Files.createDirectories(file.getParent());
            List<String> ordered = new ArrayList<>(dirs);
            Collections.sort(ordered);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), ordered);
            log.info("已更新 momentaMXF 记录: {} (共 {} 个)", file, ordered.size());
        } catch (IOException e) {
            log.error("写入 momentaMXF 记录失败", e);
        }
    }

    private Path momentaMXFRecordFilePath() {
        if (momentaMXFRecordFile == null || momentaMXFRecordFile.isBlank()) {
            return Path.of(localDir).resolve("download-momenta-mxf-subdirs.json");
        }
        return Path.of(momentaMXFRecordFile);
    }

    /* ==================== 按天导入历史数据 ==================== */

    /**
     * 按天导入历史数据：从 {@code dims.download.history-start-date}（默认 2026-09-01）00:00:00 起，
     * 逐日（含当天）导入每一天的数据。
     *
     * <p>每天的导入流程与 {@link #downloadDataBySubdir()} 保持一致：扫描
     * {@code {bosPrefix}/{yyyyMMdd}/} 前缀下所有对象并按子集目录分组，逐个子集目录下载并导入；
     * 某个子集目录下载+导入成功后，把其 key 记入当天的独立 JSON 记录文件
     * （默认 {@code {local-dir}/{yyyy-MM-dd}.json}），下次处理该天时对已记录的子集目录直接跳过，
     * 实现按天幂等去重与断点续导。</p>
     *
     * <p>历史回填属一次性批量操作，为避免误触发，未挂 {@code @Scheduled}；如需定时可取消下方注释。</p>
     */
//    @Scheduled(cron = "0 0/1 * * * ? ")
    public void importDataByDay() {
        log.info("importDataByDay 开始");
        if (!runningImportByDay.compareAndSet(false, true)) {
            log.info("上一次按天导入任务仍在执行，本次调度跳过");
            return;
        }

        if (!"true".equalsIgnoreCase(historyFlag)) {
            log.info("historyFlag 不为 true，本次调度跳过");
            return;
        }

        String lockKey = "lock:importDataByDay";
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(10, 60, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("importDataByDay 获取锁失败");
                return;
            }

            LocalDate start = LocalDate.parse(historyStartDate, DateTimeFormatter.ISO_LOCAL_DATE);
//            LocalDate today = LocalDate.now();
            LocalDate today = LocalDate.parse(historyEndDate, DateTimeFormatter.ISO_LOCAL_DATE);
            if (start.isAfter(today)) {
                log.info("起始日期[{}]晚于今天[{}]，无可导入数据", start, today);
                return;
            }
            log.info("按天导入开始：从 [{}] 到 [{}]（含）逐日导入", start, today);

            int importedDays = 0;
            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                if (importOneDay(day)) {
                    importedDays++;
                }
            }
            log.info("按天导入完成，共成功导入 {} 天", importedDays);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("importDataByDay 获取分布式锁被中断", e);
        } catch (Exception e) {
            log.error("按天导入历史数据异常：{}", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            runningImportByDay.set(false);
        }
    }

    /**
     * 导入某一天的数据，流程仿照 {@link #downloadDataBySubdir()}：
     * ① 扫描当天前缀下所有对象并按子集目录分组；
     * ② 读取当天记录文件中已导入的子集目录；
     * ③ 逐个下载并导入未记录的子集目录，导入成功后把该子集目录 key 记入当天记录文件。
     *
     * @return 当天是否至少有一个子集目录被成功导入（含此前已记录导致直接跳过的情形）
     */
    private boolean importOneDay(LocalDate day) {
        String dayPrefix = buildDayPrefix(day);
        Path dayRecordFile = dayRecordFilePath(day);
        log.info("开始按天导入：日期[{}] 前缀[{}] 记录文件[{}]", day, dayPrefix, dayRecordFile);

        // 1. 扫描当天前缀下所有对象，按子集目录分组
        Map<String, Set<BosObjectSummary>> subdirs = groupBySubdir(dayPrefix);
        if (subdirs.isEmpty()) {
            log.info("日期[{}] (前缀[{}]) 下无对象，无可导入数据", day, dayPrefix);
            return false;
        }
        log.info("日期[{}] 扫描到子集目录 {} 个: {}", day, subdirs.size(), subdirs.keySet());

        // 2. 读取当天已导入的子集目录记录（幂等去重）
        Set<String> importedDirs = loadDayImportedDirs(dayRecordFile);
        log.debug("日期[{}] 已导入子集目录: {}", day, importedDirs);

        // 3. 逐个下载并导入未记录的子集目录，导入成功后即时写入当天记录文件
        boolean anySuccess = false;
        for (Map.Entry<String, Set<BosObjectSummary>> e : subdirs.entrySet()) {
            String subDir = e.getKey();
            if (importedDirs.contains(subDir)) {
                log.info("日期[{}] 子集目录已导入，跳过: {}", day, subDir);
                anySuccess = true;
                continue;
            }
            try {
                downloadSubdir(subDir, e.getValue());

//                DirectoryImportRequest directoryImportRequest = new DirectoryImportRequest();
//                // subDir 已含 bosPrefix（groupBySubdir 返回 {bosPrefix}/{day}/{folder}），
//                // 直接拼 localDir，避免 bosPrefix 重复导致目录不存在
//                directoryImportRequest.setDirectory(localDir + "/" + subDir);
//                directoryImportRequest.setSource("MMT");
//                localDirectoryImportService.importDirectory(directoryImportRequest);
            } catch (Exception ex) {
                log.error("日期[{}] 子集目录导入失败，本次不记录，下次将重试: {}", day, subDir, ex);
                continue;
            }
            // 下载+导入成功后才记录，保证该子集目录作为一个完整过程原子落记
            importedDirs.add(subDir);
            saveDayImportedDirs(dayRecordFile, importedDirs);
            anySuccess = true;
            log.info("日期[{}] 子集目录导入成功并已记录: {}", day, subDir);
        }
        return anySuccess;
    }

    /**
     * 某一天的记录文件路径：默认 {@code {local-dir}/{yyyy-MM-dd}.json}。
     */
    private Path dayRecordFilePath(LocalDate day) {
        String fileName = day.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".json";
        return Path.of(localDir).resolve(fileName);
    }

    /**
     * 读取某天记录文件中已导入的子集目录 key 列表（文件不存在时返回空集合）。
     */
    private Set<String> loadDayImportedDirs(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        try {
            List<String> list = objectMapper.readValue(
                    file.toFile(), new TypeReference<List<String>>() {
                    });
            return new LinkedHashSet<>(list);
        } catch (IOException e) {
            log.warn("读取当天导入记录失败，按空记录处理: {}", file, e);
            return new LinkedHashSet<>();
        }
    }

    /**
     * 把某天已导入的子集目录 key 列表持久化为当天的 JSON 记录文件（升序写入）。
     */
    private void saveDayImportedDirs(Path file, Collection<String> dirs) {
        try {
            Files.createDirectories(file.getParent());
            List<String> ordered = new ArrayList<>(dirs);
            Collections.sort(ordered);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), ordered);
            log.info("已更新当天导入记录: {} (共 {} 个)", file, ordered.size());
        } catch (IOException e) {
            log.error("写入当天导入记录失败", e);
        }
    }

    /**
     * 分页列出 {@code dayPrefix} 前缀下所有对象，按子集目录分组。
     *
     * <p>分组粒度为「继续下沉一级的子目录」：{@code {dayPrefix}/{folder}/{subFolder}}
     * （无下一级时退化为 {@code {dayPrefix}/{folder}}），详见 {@link #resolveSubdir}；
     * 记录文件与幂等去重均以该目录为单位。</p>
     *
     * <p>只纳入「上传完成时间距今已超过 {@code uploadLagMinutes} 分钟」的稳定对象，
     * 跳过刚上传/可能仍在写入中的对象（未满窗口的对象本次不参与，等待下次调度补齐）。</p>
     *
     * @return key 为子集目录完整前缀，value 为该目录下所有对象
     */
    private Map<String, Set<BosObjectSummary>> groupBySubdir(String dayPrefix) {
        Map<String, Set<BosObjectSummary>> map = new LinkedHashMap<>();
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest(bucket);
            request.setMaxKeys(1000);
            request.setPrefix(dayPrefix);
            if (marker != null) {
                request.setMarker(marker);
            }
            ListObjectsResponse response = bosClient.listObjects(request);

            for (BosObjectSummary obj : response.getContents()) {
                String key = obj.getKey();
                // 跳过目录占位符（空对象）
                if (key.endsWith("/")) {
                    continue;
                }
                // 只处理上传完成时间距今已超 uploadLagMinutes 分钟的稳定对象
                if (!isMature(obj)) {
                    log.debug("对象上传未满 {} 分钟，本次跳过: {}", uploadLagMinutes, key);
                    continue;
                }
                String subDir = resolveSubdir(key, dayPrefix);
                if (subDir == null) {
                    continue;
                }
                map.computeIfAbsent(subDir, k -> new LinkedHashSet<>()).add(obj);
            }
            marker = response.isTruncated() ? response.getNextMarker() : null;
        } while (marker != null);
        return map;
    }

    /**
     * 判断对象是否「稳定」可处理：上传完成时间（lastModified）距今至少已过 {@code uploadLagMinutes} 分钟。
     * 跳过刚上传/可能仍在写入中的对象；{@code uploadLagMinutes <= 0} 时不限制；lastModified 缺失时视为可处理。
     */
    private boolean isMature(BosObjectSummary obj) {
        if (uploadLagMinutes <= 0 || obj.getLastModified() == null) {
            return true;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(uploadLagMinutes));
        return obj.getLastModified().toInstant().isBefore(cutoff);
    }

    /**
     * 组装某一天的日目录前缀：{@code {bosPrefix}/{yyyyMMdd}/}（bosPrefix 为空时直接 {@code {yyyyMMdd}/}）。
     */
    private String buildDayPrefix(LocalDate day) {
        String dayStr = day.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return (bosPrefix == null || bosPrefix.isBlank())
                ? dayStr + "/"
                : bosPrefix + "/" + dayStr + "/";
    }

    /**
     * 计算「近 {@code recentHours} 小时」处理窗口内涉及的所有日目录前缀（从旧到新排列）。
     *
     * <p>对象按日目录 {@code {yyyyMMdd}} 组织；为覆盖最近 {@code recentHours} 小时的数据，
     * 需包含今天并往前足够覆盖该时长的历史日目录（保守多取不影响正确性——成熟度过滤
     * {@code uploadLagMinutes} 与子集目录幂等记录已兜底）。{@code recentHours <= 0} 时仅处理今天。</p>
     */
    private List<String> recentDayPrefixes(LocalDate today) {
        List<String> prefixes = new ArrayList<>();
        long daysBack = recentHours <= 0 ? 0 : (recentHours + 23) / 24;
        for (long i = daysBack; i >= 0; i--) {
            prefixes.add(buildDayPrefix(today.minusDays(i)));
        }
        return prefixes;
    }

    /**
     * 解析对象 key 所属的子集目录（当天前缀下继续下沉一级的子目录）。
     *
     * <p>key 形如 {@code {dayPrefix}/{folder}/{subFolder}/{file}} 时返回
     * {@code {dayPrefix}/{folder}/{subFolder}}（即在一级子目录 folder 的基础上继续下一级）；
     * 若文件直接落在 {@code {dayPrefix}/{folder}/} 下（没有下一级子目录），
     * 则退化为返回 {@code {dayPrefix}/{folder}}；若文件直接落在当天目录下则返回 {@code null}。</p>
     */
    private String resolveSubdir(String key, String dayPrefix) {
        if (!key.startsWith(dayPrefix)) {
            return null;
        }
        String rel = key.substring(dayPrefix.length());
        // 一级子目录（folder）
        int first = rel.indexOf('/');
        if (first <= 0) {
            return null; // 无子集目录层
        }
        // 剩余部分中再找一次分隔符 → 二级子目录（subFolder）
        int second = rel.indexOf('/', first + 1);
        return second > 0 ? dayPrefix + rel.substring(0, second) : dayPrefix + rel.substring(0, first);
    }

    /**
     * 取子集目录所属的一级子目录（session 目录）：用于保持原「按一级子目录导入」的粒度。
     *
     * <p>{@code subDir} 形如 {@code {dayPrefix}/{folder}} 或 {@code {dayPrefix}/{folder}/{subFolder}}，
     * 返回 {@code {dayPrefix}/{folder}}；若未能匹配到任何日目录前缀则原样返回。</p>
     */
    private String resolveParentSubdir(String subDir, List<String> dayPrefixes) {
        if (subDir == null || dayPrefixes == null) {
            return subDir;
        }
        for (String prefix : dayPrefixes) {
            if (prefix != null && !prefix.isBlank() && subDir.startsWith(prefix)) {
                String rel = subDir.substring(prefix.length());
                int slash = rel.indexOf('/');
                return slash > 0 ? prefix + rel.substring(0, slash) : prefix + rel;
            }
        }
        return subDir;
    }

    /**
     * 作为完整下载过程下载一个子集目录下的所有对象到本地（保持目录结构）。
     */
    private void downloadSubdir(String subDir, Set<BosObjectSummary> objects) throws IOException {
        for (BosObjectSummary obj : objects) {
            log.debug("下载的子集目录是：{}", subDir);
            String key = obj.getKey();
            // 命名包含forever_log 跳过
            if (key.contains("forever_log")) {
                continue;
            }

            Path localFile = resolveLocalPath(key);
            Files.createDirectories(localFile.getParent());

            GetObjectRequest getRequest = new GetObjectRequest(bucket, key);
            bosClient.getObject(getRequest, localFile.toFile());
            log.info("下载完成: {} -> {} ({} 字节)", key, localFile, obj.getSize());
        }
    }

    /**
     * meta-only 模式下载：只拉取子集目录下用于事件统计的 meta 文件与前视鱼眼 h265
     * （保持目录结构），供 {@code importDirectoryMetaOnly} 建切片并生成鱼眼预览。
     * 其余对象由用户点击「加载」后按需全量拉取。
     */
    private void downloadSubdirMetaOnly(String subDir, Set<BosObjectSummary> objects) throws IOException {
        int downloaded = 0;
        for (BosObjectSummary obj : objects) {
            String key = obj.getKey();
            // 日志文件元数据已登记，本体不参与 meta-only 下载
            if (key.contains("_log")) {
                continue;
            }
            if (!isMetaOrFisheyeKey(key)) {
                continue;
            }

            Path localFile = resolveLocalPath(key);
            // 幂等：已存在且大小一致时跳过（调度重试场景）
            if (Files.exists(localFile) && Files.size(localFile) == obj.getSize()) {
                continue;
            }
            Files.createDirectories(localFile.getParent());

            GetObjectRequest getRequest = new GetObjectRequest(bucket, key);
            bosClient.getObject(getRequest, localFile.toFile());
            downloaded++;
            log.info("meta-only 下载完成: {} -> {} ({} 字节)", key, localFile, obj.getSize());
        }
        log.info("meta-only 子集目录[{}]下载完成：命中 meta/前视鱼眼对象 {} 个 / 共 {} 个对象",
                subDir, downloaded, objects.size());

        // 日志文件（forever_log / bag_log 等）：本体不下载，仅登记元数据供「日志浏览」页面展示
        // 与整目录打包下载（元数据来源：同会话目录下的 meta 文件 + 对象 key 所在目录）。
        // 放在 meta 下载之后，元数据可优先复用刚落地的本地 meta 文件，避免额外 BOS 读取。
        recordLogObjects(subDir, objects);
    }

    /**
     * 登记子集目录下所有 {@code _log} 日志文件的元数据（写入 log_record）。
     *
     * <p>日志文件本体不下载；元数据来自同会话目录下逐级向上查找到的 meta 文件，
     * 以及对象 key 所在目录。登记失败只告警，不影响 meta-only 导入主流程。</p>
     */
    private void recordLogObjects(String subDir, Set<BosObjectSummary> objects) {
        boolean hasLog = objects.stream()
                .anyMatch(o -> o.getKey() != null && o.getKey().contains("_log"));
        if (!hasLog) {
            return;
        }
        try {
            int saved = logRecordService.recordLogObjects(bucket, subDir, objects);
            log.info("子集目录[{}]日志记录登记完成：{} 条 / 对象 {} 个", subDir, saved, objects.size());
        } catch (Exception e) {
            log.warn("子集目录[{}]日志记录登记失败，不影响 meta-only 导入: {}", subDir, e.getMessage(), e);
        }
    }

    /**
     * 判断 BOS 对象 key 是否为 meta-only 模式需要下载的文件：
     * 文件名为 meta（事件统计元信息），或前视候选摄像头的 h265
     * （优先 generated/topics/images/F100_front_wide*.h265，数据包中无广角视频流时
     * 降级播放前视鱼眼 generated/topics/images/F195_front_fisheye*.h265）。
     */
    private boolean isMetaOrFisheyeKey(String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        if ("meta".equals(fileName)) {
            return true;
        }
        return FisheyePreviewService.PREVIEW_SENSOR_CANDIDATES.stream()
                .anyMatch(fileName::startsWith);
    }

    /* ==================== 子集目录下载记录（独立文件） ==================== */

    /**
     * 读取已下载子集目录 key 列表。
     */
    private Set<String> loadDownloadedSubdirs() {
        Path file = subdirRecordFilePath();
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        try {
            List<String> list = objectMapper.readValue(
                    file.toFile(), new TypeReference<List<String>>() {
                    });
            return new LinkedHashSet<>(list);
        } catch (IOException e) {
            log.warn("读取子集目录记录失败，按空记录处理: {}", file, e);
            return new LinkedHashSet<>();
        }
    }

    /**
     * 持久化已下载子集目录 key 列表（升序写入）。
     */
    private void saveDownloadedSubdirs(Collection<String> subdirs) {
        try {
            Path file = subdirRecordFilePath();
            Files.createDirectories(file.getParent());
            List<String> ordered = new ArrayList<>(subdirs);
            Collections.sort(ordered);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), ordered);
            log.info("已更新子集目录下载记录: {} (共 {} 个)", file, ordered.size());
        } catch (IOException e) {
            log.error("写入子集目录下载记录失败", e);
        }
    }

    private Path subdirRecordFilePath() {
        if (subdirRecordFile == null || subdirRecordFile.isBlank()) {
            return Path.of(localDir).resolve("download-subdirs.json");
        }
        return Path.of(subdirRecordFile);
    }

    /**
     * 解析对象 key 在 bosPrefix 下的第二个子集目录（文件夹）。
     *
     * <p>对象结构为 {@code {bosPrefix}/{yyyy-MM-dd}/{文件夹}/{文件}}，相对 bosPrefix 路径为
     * {@code {yyyy-MM-dd}/{文件夹}/{文件}}，其中第二个子集目录即「文件夹」；例如
     * key={@code .../jiangling-...-ICA/2026-08-26/session01/1.jpg}、bosPrefix={@code mmt-ads/.../jiangling-...-ICA}
     * 时返回 {@code session01}；若文件未落到第二个子目录（无文件夹层），则返回 {@code null}。</p>
     */
    private String resolveParentDir(String key) {
        String rel = key;
        if (bosPrefix != null && !bosPrefix.isBlank() && key.startsWith(bosPrefix)) {
            rel = key.substring(bosPrefix.length());
        }
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        if (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }
        String[] parts = rel.split("/");
        // 结构: {yyyy-MM-dd}/{文件夹}/{文件} → 第二段（下标 1）即文件夹
        if (parts.length < 3) {
            return null;
        }
        String second = bosPrefix + "/" + parts[0] + "/" + parts[1];
        return second.isBlank() ? null : second;
    }

    /**
     * 把对象 key 映射为本地落地路径（保持目录结构，防止前缀/目录穿越）。
     */
    private Path resolveLocalPath(String key) {
        String cleanKey = key.startsWith("/") ? key.substring(1) : key;
        Path localDirPath = Path.of(localDir);
        Path relative = Path.of("").resolve(cleanKey).normalize();
//        if (relative.isAbsolute() || !relative.startsWith(Path.of(""))) {
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("非法对象 key: " + key);
        }
        return localDirPath.resolve(relative);
    }

    /**
     * 组装下载记录。
     */
    private DownloadRecord toRecord(BosObjectSummary obj, Path localFile) {
        DownloadRecord record = new DownloadRecord();
        record.setObjectKey(obj.getKey());
        record.setLocalPath(localFile.toString());
        record.setSize(obj.getSize());
        record.setLastModified(obj.getLastModified() == null
                ? null
                : obj.getLastModified().toInstant());
        record.setDownloadedAt(Instant.now());
        return record;
    }

    /**
     * 从记录文件中读取最后一次上传完成时间（所有记录中 lastModified 的最大值）。
     */
    private Optional<Instant> latestLastModified() {
        return loadRecords().stream()
                .map(DownloadRecord::getLastModified)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
    }

    /**
     * 判断对象是否已在 JSON 记录文件中（同 key 且最后修改时间一致）。
     */
    private boolean alreadyDownloaded(String key, Instant lastModified) {
        return loadRecords().stream().anyMatch(r ->
                key.equals(r.getObjectKey())
                        && Math.abs(r.getLastModified().getEpochSecond() - lastModified.getEpochSecond())
                        <= LAST_MODIFIED_TOLERANCE_SECONDS);
    }

    /**
     * 追加新下载记录到记录文件（合并历史记录，按下载时间升序，最新在末尾）。
     */
    private void appendRecords(List<DownloadRecord> newRecords) {
        List<DownloadRecord> all = loadRecords();
        all.addAll(newRecords);
        all.sort(Comparator.comparing(DownloadRecord::getDownloadedAt)
                .thenComparing(DownloadRecord::getObjectKey));
        writeRecords(all);
    }

    private List<DownloadRecord> loadRecords() {
        Path file = recordFilePath();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<List<DownloadRecord>>() {
            });
        } catch (IOException e) {
            log.warn("读取下载记录失败，按空记录处理: {}", file, e);
            return new ArrayList<>();
        }
    }

    private void writeRecords(List<DownloadRecord> records) {
        try {
            Path file = recordFilePath();
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), records);
            log.info("已更新下载记录文件: {} (共 {} 条)", file, records.size());
        } catch (IOException e) {
            log.error("写入下载记录失败", e);
        }
    }

    private Path recordFilePath() {
        if (recordFile == null || recordFile.isBlank()) {
            return Path.of(localDir).resolve("download-record.json");
        }
        return Path.of(recordFile);
    }
}
