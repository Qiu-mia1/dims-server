package com.isim.dims.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.DirectoryImportRequest;
import com.isim.dims.service.FisheyePreviewService;
import com.isim.dims.service.LocalDirectoryImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

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
 * @feature: ingestion
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
//@ConditionalOnProperty(name = "dims.storage.type", havingValue = "bos")
public class DownloadMMTDataS3Schedule {

    /**
     * 幂等判定时最后修改时间允许的误差（秒），避免 SDK 时间精度抖动导致重复下载
     */
    private static final long LAST_MODIFIED_TOLERANCE_SECONDS = 60;

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    private final RedissonClient redissonClient;

    @Autowired
    private LocalDirectoryImportService localDirectoryImportService;

    /**
     * 防重入标志：downloadDataZytSource 正在执行时为 true，
     * 下一次调度触发时直接跳过，避免并发扫描/下载/导入。
     * （非 final：避免被 @RequiredArgsConstructor 纳入构造器注入）
     */
    private AtomicBoolean running = new AtomicBoolean(false);

    @Value("${minio.download-bucket: mmt_ads}")
    private String bucket;

    //    @Value("${dims.download.bos-prefix: mmt-ads/standard/production_car/source/jiangling-MMT-CX835-ICA}")
    @Value("${dims.download.bos-prefix: mmt_test}")
    private String bosPrefix;

    @Value("${dims.download.local-dir: /data/downloads}")
    private String localDir;

    @Value("${dims.download.record-file:}")
    private String recordFile;

    @Value("${dims.download.subdir-record-file:}")
    private String subdirRecordFile;

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
                            .orElse(Instant.parse("2026-08-06T00:00:00Z"));
            log.info("扫描 BOS 桶[{}]前缀[{}]，仅处理最后修改时间晚于 {} 的对象",
                    bucket, bosPrefix, lastProcessedTime);

            List<DownloadRecord> downloaded = new ArrayList<>();
            // 保存 bosPrefix 下出现的一级目录（去重）；需声明在分页循环外，避免翻页时被重置
            Set<String> parentDirs = new HashSet<>();
            String continuationToken = null;

            do {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .maxKeys(1000)
                        .continuationToken(continuationToken);
                if (bosPrefix != null && !bosPrefix.isBlank()) {
                    request.prefix(bosPrefix);
                }

                ListObjectsV2Response response = s3Client.listObjectsV2(request.build());

                for (S3Object obj : response.contents()) {
                    String key = obj.key();

                    // 跳过目录占位符（空对象）
                    if (key.endsWith("/") && obj.size() == 0) {
                        continue;
                    }

                    Instant lastModified = obj.lastModified();
                    // 增量：只处理最后修改时间晚于最后记录时间的对象
                    if (!lastModified.isAfter(lastProcessedTime)) {
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

                        GetObjectRequest getRequest = GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build();
                        s3Client.getObject(getRequest, localFile);

                        downloaded.add(toRecord(obj, localFile));
                        log.info("下载完成: {} -> {} ({} 字节)", key, localFile, obj.size());
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

                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null);

            log.info("扫描到 bosPrefix 下第二个子集目录 {} 个: {}", parentDirs.size(), parentDirs);

            if (downloaded.isEmpty()) {
                log.info("本次无晚于最后记录时间的新对象，无新增下载");
                return;
            }
            appendRecords(downloaded);
            log.info("本次下载 {} 个文件，最新记录: {}", downloaded.size(), downloaded.get(downloaded.size() - 1));
        } finally {
            running.set(false);
        }
    }

    /**
     * 按「子集目录」逐日下载：下载 {@code bosPrefix/(yyyyMMdd 今天)/子集目录} 下所有对象，
     * 每个子集目录（第二级目录，如 {@code bosPrefix/today/session01/}）作为一个完整下载过程。
     *
     * <p>下载完成后把该子集目录 key 记入独立 JSON 记录文件（默认
     * {@code {local-dir}/download-subdirs.json} 或 {@code dims.download.subdir-record-file}），
     * 下次调度遇到已记录的子集目录直接跳过，实现按目录幂等去重。</p>
     *
     * <p>对象结构约定：{@code {bosPrefix}/{yyyy-MM-dd}/{子集目录}/{文件}}。</p>
     *
     * <p>除今天外，还会处理近 {@code recentHours} 小时（默认 48，可配置
     * {@code dims.download.recent-hours}）内所涉及的所有历史日目录：把因上传延迟而落在前几日目录、
     * 尚未处理的对象也一并归并处理，避免跨天/延迟晚到数据被漏掉。</p>
     *
     * <p><b>meta-only 模式</b>：每个子集目录只下载 meta（事件统计元信息）与前视鱼眼 h265，
     * 导入仅建切片（status=META_READY）并异步转码鱼眼预览 mp4；用户在数据浏览器点
     * 「加载」时，后端再按切片记录的 import_bos_key 全量拉取并走完整导入流水线。</p>
     */
//    @Scheduled(cron = "0 0/1 * * * ? ")
    public void downloadDataBySubdir() {
        // 防重入：多实例部署下用分布式锁保证同一时刻只有一个节点执行
        String lockKey = "lock:downloadDataBySubdirS3";
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(10, 60, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("downloadDataBySubdir(S3) 获取锁失败");
                return;
            }

            LocalDate today = LocalDate.now();
            // 处理窗口 = 近 recentHours 小时所覆盖到的各日目录（默认 48 → 今天 + 昨天），
            // 按日期从旧到新排列，保证日志与记录文件顺序稳定
            List<String> processingPrefixes = recentDayPrefixes(today);
            log.info("扫描 S3 桶[{}]近 {} 小时内的日目录前缀 {} 个: {}",
                    bucket, recentHours, processingPrefixes.size(), processingPrefixes);

            // 1. 扫描处理窗口内各日前缀下所有对象，按子集目录分组（key = 子集目录完整前缀）
            Map<String, Set<S3Object>> subdirs = new LinkedHashMap<>();
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

            // 3. 逐个下载未记录的子集目录，下载成功后记录
            int processed = 0;
            for (Map.Entry<String, Set<S3Object>> e : subdirs.entrySet()) {
                String subDir = e.getKey();
                if (downloadedDirs.contains(subDir)) {
                    log.info("子集目录已下载，跳过: {}", subDir);
                    continue;
                }
                try {
                    // meta-only 模式：只拉取用于事件统计的 meta 与前视鱼眼 h265（保持目录结构），
                    // 其余文件待用户在数据浏览器点「加载」时按 slice.import_bos_key 全量拉取
                    downloadSubdirMetaOnly(subDir, e.getValue());

                    DirectoryImportRequest directoryImportRequest = new DirectoryImportRequest();
                    // subDir 已含 bosPrefix（groupBySubdir 返回 {bosPrefix}/{today}/{folder}），
                    // 直接拼 localDir，避免 bosPrefix 重复导致目录不存在
                    directoryImportRequest.setDirectory(localDir + "/" + subDir);
                    directoryImportRequest.setSource("MMT");
                    directoryImportRequest.setMetaOnly(true);
                    directoryImportRequest.setBosKeyPrefix(subDir);
                    // meta-only 导入：仅解析 meta 建切片（META_READY）并异步转码前视鱼眼预览，
                    // 不校验 mfbag、不跑转换流水线
                    localDirectoryImportService.importDirectoryMetaOnly(directoryImportRequest);

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
            throw new RuntimeException("downloadDataBySubdir(S3) 获取分布式锁被中断", e);
        } catch (Exception e) {
            log.error("按子集目录下载异常：{}", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
     * 分页列出 {@code dayPrefix} 前缀下所有对象，按子集目录（第二级目录）分组。
     *
     * <p>只纳入「上传完成时间距今已超过 {@code uploadLagMinutes} 分钟」的稳定对象，
     * 跳过刚上传/可能仍在写入中的对象（未满窗口的对象本次不参与，等待下次调度补齐）。</p>
     *
     * @return key 为子集目录完整前缀（{@code dayPrefix + folder}），value 为该目录下所有对象
     */
    private Map<String, Set<S3Object>> groupBySubdir(String dayPrefix) {
        Map<String, Set<S3Object>> map = new LinkedHashMap<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .maxKeys(1000)
                    .prefix(dayPrefix)
                    .continuationToken(continuationToken);
            ListObjectsV2Response response = s3Client.listObjectsV2(request.build());

            for (S3Object obj : response.contents()) {
                String key = obj.key();
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
            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return map;
    }

    /**
     * 判断对象是否「稳定」可处理：上传完成时间（lastModified）距今至少已过 {@code uploadLagMinutes} 分钟。
     * 跳过刚上传/可能仍在写入中的对象；{@code uploadLagMinutes <= 0} 时不限制；lastModified 缺失时视为可处理。
     */
    private boolean isMature(S3Object obj) {
        if (uploadLagMinutes <= 0 || obj.lastModified() == null) {
            return true;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(uploadLagMinutes));
        return obj.lastModified().isBefore(cutoff);
    }

    /**
     * 解析对象 key 所属的子集目录（当天前缀下的第一级子目录）。
     * key 形如 {@code {dayPrefix}/{folder}/{file}}，返回 {@code {dayPrefix}/{folder}}；
     * 若文件直接落在当天目录下（无子集目录层）则返回 {@code null}。
     */
    private String resolveSubdir(String key, String dayPrefix) {
        if (!key.startsWith(dayPrefix)) {
            return null;
        }
        String rel = key.substring(dayPrefix.length());
        int slash = rel.indexOf('/');
        if (slash <= 0) {
            return null; // 无子集目录层
        }
        String folder = rel.substring(0, slash);
        return dayPrefix + folder;
    }

    /**
     * 作为完整下载过程下载一个子集目录下的所有对象到本地（保持目录结构）。
     */
    private void downloadSubdir(String subDir, Set<S3Object> objects) throws IOException {
        for (S3Object obj : objects) {
            String key = obj.key();
            // 命名包含forever_log 跳过
            if (key.contains("forever_log")) {
                continue;
            }

            Path localFile = resolveLocalPath(key);
            Files.createDirectories(localFile.getParent());

            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.getObject(getRequest, localFile);
            log.info("下载完成: {} -> {} ({} 字节)", key, localFile, obj.size());
        }
    }

    /**
     * meta-only 模式下载：只拉取子集目录下用于事件统计的 meta 文件与前视鱼眼 h265
     * （保持目录结构），供 {@code importDirectoryMetaOnly} 建切片并生成鱼眼预览。
     * 其余对象由用户点击「加载」后按需全量拉取。
     */
    private void downloadSubdirMetaOnly(String subDir, Set<S3Object> objects) throws IOException {
        int downloaded = 0;
        for (S3Object obj : objects) {
            String key = obj.key();
            // 命名包含forever_log 跳过
            if (key.contains("forever_log")) {
                continue;
            }
            if (!isMetaOrFisheyeKey(key)) {
                continue;
            }

            Path localFile = resolveLocalPath(key);
            // 幂等：已存在且大小一致时跳过（调度重试场景）
            if (Files.exists(localFile) && Files.size(localFile) == obj.size()) {
                continue;
            }
            Files.createDirectories(localFile.getParent());

            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.getObject(getRequest, localFile);
            downloaded++;
            log.info("meta-only 下载完成: {} -> {} ({} 字节)", key, localFile, obj.size());
        }
        log.info("meta-only 子集目录[{}]下载完成：命中 meta/前视鱼眼对象 {} 个 / 共 {} 个对象",
                subDir, downloaded, objects.size());
    }

    /**
     * 判断 S3 对象 key 是否为 meta-only 模式需要下载的文件：
     * 文件名为 meta（事件统计元信息），或前视候选摄像头的 h265
     * （优先 generated/topics/images/F100_front_wide*.h265，数据包中无广角视频流时
     * 降级播放前视鱼眼 generated/topics/images/F195_front_fisheye*.h265）。
     */
    private boolean isMetaOrFisheyeKey(String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        if ("meta".equals(fileName)) {
            return true;
        }
        if (!fileName.endsWith(".h265")) {
            return false;
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
    private DownloadRecord toRecord(S3Object obj, Path localFile) {
        DownloadRecord record = new DownloadRecord();
        record.setObjectKey(obj.key());
        record.setLocalPath(localFile.toString());
        record.setSize(obj.size());
        record.setLastModified(obj.lastModified());
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
