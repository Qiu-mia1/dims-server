package com.isim.dims.schedule;

import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.model.BosObjectSummary;
import com.baidubce.services.bos.model.GetObjectRequest;
import com.baidubce.services.bos.model.ListObjectsRequest;
import com.baidubce.services.bos.model.ListObjectsResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.VinMd5Mapping;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.VinMd5MappingMapper;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 定时任务：从 BOS 桶 {@code jmc-data-835-source} 扫描 Hirain（恒润）源数据，
 * 仅提取元数据生成 status=META_READY 的 data_slice；用户在数据浏览器点击「加载」后
 * 才执行完整下载 → 解压 → 转换 → 上传流程。
 *
 * <p>对象目录结构（demo 示例）：</p>
 * <pre>
 *   {prefix}/{yyyyMMdd}/{VIN}/{session}_public/public/{文件}
 *   例：standard/production_car/source/jiangling-poc_test/20261013/
 *       LXWMUZDC0SHF88589/SHF88589_20261013063545722602_public/public/...
 * </pre>
 *
 * <p>处理流程（每个「子集目录」= {@code {prefix}/{yyyyMMdd}/{VIN}/{session}_public} 为一个处理单元）：</p>
 * <ol>
 *   <li>扫描近 {@code recentHours} 小时（默认 300）所覆盖的日目录前缀下所有对象，按子集目录分组；</li>
 *   <li>逐个子集目录仅提取元数据：车型（前缀）、触发时间、VIN、触发类型、zip 文件大小；</li>
 *   <li>写入 data_slice（sourceType=Hirain、vin 取自目录名、status=META_READY），
 *       并记录 import_bos_key 供后续按需全量加载；</li>
 *   <li>用户点击「加载」后，调用 {@link #loadSlice(String)} 走完整下载/解压/转换/上传流水线，
 *       最终把转换产物 zip 上传 MinIO 并将存储 key 回写到 data_slice.file_path，切片置 READY。</li>
 * </ol>
 *
 * <p>幂等去重：子集目录元数据落库成功后即把其 key 记入独立 JSON 记录文件
 * （默认 {@code {local-dir}/download-hirain-subdirs.json}），下次调度直接跳过；
 * 记录清理只保留仍处于处理窗口内的子集目录。</p>
 *
 * @feature: ingestion
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class DownloadHirain835Schedule {

    /**
     * 转换程序必填输出参数
     */
    private static final String OUT_FILE_FLAG = "-o";

    /**
     * 转换程序 cyber（record）输入参数
     */
    private static final String CYBER_FLAG = "--cyber";

    /**
     * 转换程序 video（h265）输入参数
     */
    private static final String VIDEO_FLAG = "--video";

    /**
     * 转换程序目录输入参数（找不到 record/h265 时使用）
     */
    private static final String DATA_FOLDER_FLAG = "--data_folder";

    /**
     * 下载产物压缩包后缀
     */
    private static final String ZIP_SUFFIX = ".zip";

    /**
     * converter-input-mode：强制使用 --data_folder
     */
    private static final String DATA_FOLDER_MODE = "data_folder";

    /**
     * 转换产物 zip 的对象存储 content-type
     */
    private static final String ZIP_CONTENT_TYPE = "application/zip";

    /**
     * 子集目录名中的时间戳（yyyyMMddHHmmss，后跟微秒位）
     */
    private static final Pattern SESSION_TS_PATTERN = Pattern.compile("(\\d{14})\\d*");

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ObjectMapper objectMapper;

    private final RedissonClient redissonClient;

    private final StorageAccess storageAccess;

    private final DataSliceMapper dataSliceMapper;

    private final VinMd5MappingMapper vinMd5MappingMapper;

    private final PythonExecutor pythonExecutor;

    @Autowired
    private BosClient bosClient;

    /**
     * 防重入标志：本次任务正在执行时为 true，下一次调度直接跳过。
     * （非 final：避免被 @RequiredArgsConstructor 纳入构造器注入）
     */
    private AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 源桶
     */
    @Value("${dims.download.hirain.bucket:jmc-data-835-source}")
    private String bucket;

    /**
     * 桶内数据根前缀（其下按 {yyyyMMdd}/{VIN}/{session}_public/public 组织）
     */
    @Value("${dims.download.hirain.prefix:standard/production_car/source/jiangling-poc_test}")
    private String bosPrefix;

    /**
     * 下载落地根目录
     */
    @Value("${dims.download.hirain.local-dir:/data/downloads-hirain}")
    private String localDir;

    /**
     * 转换产物输出根目录，空则默认 {local-dir}/converted
     */
    @Value("${dims.download.hirain.converted-dir:}")
    private String convertedDir;

    /**
     * 已处理子集目录记录文件，空则默认 {local-dir}/download-hirain-subdirs.json
     */
    @Value("${dims.download.hirain.record-file:}")
    private String recordFile;

    /**
     * 处理窗口（小时）：48 → 今天 + 昨天 + 前天，避免延迟上传落到旧日目录的数据被漏掉
     */
    @Value("${dims.download.hirain.recent-hours:1440}")
    private long recentHours;

    /**
     * 上传稳定性窗口：只处理上传完成时间距今超过该分钟数的对象（<=0 表示不限制）
     */
    @Value("${dims.download.hirain.upload-lag-minutes:30}")
    private long uploadLagMinutes;

    /**
     * 写入 data_slice.source_type 的固定值
     */
    @Value("${dims.download.hirain.source-type:Hirain}")
    private String sourceType;

    /**
     * 转换程序可执行文件路径
     */
    @Value("${dims.download.hirain.mcap-converter-path:/opt/dims-python/mcap_converter}")
    private String mcapConverterPath;

    /**
     * 转换程序附加参数（追加在命令末尾，如 "--verbose"）；留空则不追加
     */
    @Value("${dims.download.hirain.mcap-converter-args:}")
    private String mcapConverterArgs;

    /**
     * 转换程序输入模式：
     * <ul>
     *   <li>{@code auto}（默认）：找到 record + h265 时用 {@code --cyber/--video}，否则用 {@code --data_folder}；</li>
     *   <li>{@code data_folder}：始终用 {@code --data_folder}。</li>
     * </ul>
     */
    @Value("${dims.download.hirain.converter-input-mode:auto}")
    private String converterInputMode;

    /**
     * cyber（--cyber）文件后缀
     */
    @Value("${dims.download.hirain.converter-cyber-suffix:.record}")
    private String converterCyberSuffix;

    /**
     * video（--video）文件后缀
     */
    @Value("${dims.download.hirain.converter-video-suffix:.h265}")
    private String converterVideoSuffix;

    /**
     * 转换程序超时（分钟）
     */
    @Value("${dims.download.hirain.mcap-converter-timeout-minutes:120}")
    private long mcapConverterTimeoutMinutes;

    /**
     * 时间戳调整脚本 rewrite_record_patched.py 超时（秒）
     */
    @Value("${dims.download.hirain.rewrite-timeout-sec:600}")
    private int rewriteTimeoutSec;

    /**
     * 转换程序输入子目录（如 public）；留空则把整个子集目录作为输入
     */
    @Value("${dims.download.hirain.converter-input-subdir:}")
    private String converterInputSubdir;

    /**
     * 打包进 zip 的转换产物后缀；留空（默认）则打包输出目录下全部文件
     */
    @Value("${dims.download.hirain.bundle-suffix:}")
    private String bundleSuffix;

    /**
     * 转换产物扩展名（per_file 模式下每个 .h265 对应一个同名产物文件）
     */
    @Value("${dims.download.hirain.output-extension:.mcap}")
    private String outputExtension;

    /**
     * 入库成功后是否删除本地下载的原始数据、转换产物及 zip 压缩包
     */
    @Value("${dims.download.hirain.cleanup-downloaded:true}")
    private boolean cleanupDownloaded;

    /**
     * 定时入口：下载 → 转换 → 入库。默认每 5 分钟执行一次。
     */
//    @Scheduled(cron = "${dims.download.hirain.cron:0 0/5 * * * ? }")
    @Scheduled(cron = "0 0/2 * * * ? ")
    public void downloadDataBySubdir() {
        if (!running.compareAndSet(false, true)) {
            log.info("上一次 Hirain 下载/转换任务仍在执行，本次调度跳过");
            return;
        }

        String lockKey = "lock:downloadHirain835BySubdir";
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;

        try {
            locked = lock.tryLock(10, 60, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("downloadHirain835BySubdir 获取锁失败");
                return;
            }

            LocalDate today = LocalDate.now();
            List<String> dayPrefixes = recentDayPrefixes(today);
            log.info("扫描 BOS 桶[{}]近 {} 小时内日目录 {} 个: {}", bucket, recentHours,
                    dayPrefixes.size(), dayPrefixes);

            // 1. 逐日列出对象，按子集目录分组
            Map<String, Set<BosObjectSummary>> subdirs = new LinkedHashMap<>();
            for (String dayPrefix : dayPrefixes) {
                subdirs.putAll(groupBySubdir(dayPrefix));
            }
            if (subdirs.isEmpty()) {
                log.info("Hirain 处理窗口内无对象，本次无下载");
                return;
            }
            log.info("Hirain 扫描到子集目录 {} 个: {}", subdirs.size(), subdirs.keySet());

            // 2. 读取已处理记录，仅保留仍处于处理窗口内的记录
            Set<String> processedDirs = loadProcessedSubdirs();
            int beforeClean = processedDirs.size();
            if (processedDirs.removeIf(d -> dayPrefixes.stream().noneMatch(d::startsWith))) {
                log.info("已清理 {} 条窗口外 Hirain 旧记录，仅保留 {} 条",
                        beforeClean - processedDirs.size(), processedDirs.size());
                saveProcessedSubdirs(processedDirs);
            }

            // 3. 逐个处理未记录的子集目录：仅提取元数据并建 META_READY 切片，
            //    全量下载/转换/上传留给用户在数据浏览器点击「加载」后执行。
            int processed = 0;
            for (Map.Entry<String, Set<BosObjectSummary>> e : subdirs.entrySet()) {
                String subDir = e.getKey();
                if (processedDirs.contains(subDir)) {
                    log.debug("Hirain 子集目录已处理，跳过: {}", subDir);
                    continue;
                }
                try {
                    createMetaSlice(subDir, e.getValue(), null);
                } catch (Exception ex) {
                    log.error("Hirain 子集目录元数据建表失败，本次不记录，下次将重试: {}", subDir, ex);
                    continue;
                }
                // 元数据落库成功后才记录，保证子集目录作为完整过程原子落记
                processedDirs.add(subDir);
                saveProcessedSubdirs(processedDirs);
                processed++;
                log.info("Hirain 子集目录元数据切片已生成: {}", subDir);
            }
            log.info("本次新处理 Hirain 子集目录 {} 个", processed);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("downloadHirain835BySubdir 获取分布式锁被中断", ie);
        } catch (Exception e) {
            log.error("Hirain 按子集目录下载/转换异常：{}", e.getMessage(), e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            running.set(false);
        }
    }

    /* ==================== 单子集目录处理流程 ==================== */

    /**
     * 处理一个子集目录：先建 META_READY 切片，再立即执行完整下载/转换/上传流水线。
     * 当前主要用于「重新拉取」场景；定时任务已改为仅调用 {@link #createMetaSlice}。
     *
     * @param subDir    子集目录完整前缀 {@code {bosPrefix}/{yyyyMMdd}/{VIN}/{session}_public}
     * @param objects   该子集目录下的所有 BOS 对象
     * @param sliceName 切片名称（重新拉取时保留原名称；定时任务首次处理传 null，使用 sessionId）
     * @return 处理完成后的 data_slice 实体
     */
    private DataSlice processOneSubdir(String subDir, Set<BosObjectSummary> objects, String sliceName)
            throws IOException, InterruptedException {
        DataSlice slice = createMetaSlice(subDir, objects, sliceName);
        return processExistingSlice(slice, objects);
    }

    /**
     * 仅扫描 BOS 元数据并创建 status=META_READY 的 data_slice。
     * 全量下载/解压/转换/上传流程留给用户点击「加载」后执行。
     *
     * @param subDir    子集目录完整前缀 {@code {bosPrefix}/{yyyyMMdd}/{VIN}/{session}_public}
     * @param objects   该子集目录下的所有 BOS 对象
     * @param sliceName 切片名称（重新拉取时保留原名称；定时任务首次处理传 null，使用 sessionId）
     * @return 新创建的 data_slice 实体
     */
    private DataSlice createMetaSlice(String subDir, Set<BosObjectSummary> objects, String sliceName) {
        String vin = resolveVin(subDir);
        String sessionId = subDir.substring(subDir.lastIndexOf('/') + 1);
        log.info("开始生成 Hirain 元数据切片[{}]：vin={} session={} 对象 {} 个",
                subDir, vin, sessionId, objects.size());

        // 1. 从 BOS 对象中解析触发类型与 zip 文件大小
        String eventName = resolveEventNameFromObjects(objects);
        long zipSize = resolveZipSize(objects);

        // 2. 组装并落库（status=META_READY，与 MMT meta-only 导入保持一致）
        DataSlice slice = buildMetaSlice(subDir, vin, sessionId, sliceName, eventName);
        slice.setFileSize(zipSize);
        dataSliceMapper.insert(slice);

        log.info("Hirain 元数据切片已生成: id={} vin={} session={} eventName={} zipSize={}",
                slice.getId(), vin, sessionId, eventName, zipSize);
        return slice;
    }

    /**
     * 对已有 data_slice 执行完整下载 → 解压 zip → 转换 → 打包 zip → 上传存储流程。
     * 用于用户点击「加载」或「重新拉取」。
     *
     * @param slice   已存在的 data_slice（status 通常为 META_READY / ERROR / LOADING）
     * @param objects 该子集目录下的所有 BOS 对象
     * @return 更新后的 data_slice 实体
     */
    private DataSlice processExistingSlice(DataSlice slice, Set<BosObjectSummary> objects)
            throws IOException, InterruptedException {
        String subDir = slice.getImportBosKey();
        String vin = slice.getVin();
        String sessionId = slice.getSessionId();
        log.info("开始加载 Hirain 子集目录[{}]：sliceId={} vin={} session={} 对象 {} 个",
                subDir, slice.getId(), vin, sessionId, objects.size());

        // 1. 下载
        Path localSubDir = downloadSubdir(subDir, objects);
        // 若元数据阶段未解析出触发类型，下载后兜底再试一次
        if (slice.getEventName() == null) {
            slice.setEventName(resolveEventName(localSubDir));
        }

        // 2. 解压下载的 zip（mcap_converter 需要 record/h265 原始文件）
        List<Path> extractedDirs = extractZips(localSubDir);

        // 3. 定位转换输入：一个 cyber（.record）+ 多个 video（.h265）
        Path searchRoot = localSubDir;
        if (converterInputSubdir != null && !converterInputSubdir.isBlank()) {
            Path candidate = localSubDir.resolve(converterInputSubdir.trim());
            if (Files.isDirectory(candidate)) {
                searchRoot = candidate;
            }
        }
        Path cyberFile = findFirstFile(searchRoot, converterCyberSuffix);
        List<Path> videoFiles = findFiles(searchRoot, converterVideoSuffix);
        if ((cyberFile == null || videoFiles.isEmpty()) && !searchRoot.equals(localSubDir)) {
            if (cyberFile == null) {
                cyberFile = findFirstFile(localSubDir, converterCyberSuffix);
            }
            if (videoFiles.isEmpty()) {
                videoFiles = findFiles(localSubDir, converterVideoSuffix);
            }
        }
        Path dataFolder = extractedDirs.isEmpty() ? searchRoot : extractedDirs.get(0);

        // 3.5 先对 record 做时间戳对齐（rewrite_record_patched.py），再交给 mcap_converter
        if (cyberFile != null && !DATA_FOLDER_MODE.equalsIgnoreCase(converterInputMode)) {
            cyberFile = rewriteCyberRecord(cyberFile);
        }

        // 4. 一次转换（--cyber record + 全部 --video h265），产物放到会话输出目录
        Path outputDir = convertedSessionDir(vin, sessionId);
        clearDirectory(outputDir);
        Files.createDirectories(outputDir);
        int converted = convertVideos(cyberFile, videoFiles, dataFolder, outputDir, sessionId);
        if (converted == 0) {
            throw new IllegalStateException("MCAP_CONVERT_EMPTY: 未生成任何转换产物 " + outputDir);
        }

        // 5. 转换产物打包成 zip，上传的就是这个 zip
        Path zipFile = convertedZip(vin, sessionId);
        long fileSize = zipConverted(outputDir, zipFile);

        // 6. 更新切片记录并上传，上传成功后回写 file_path
        slice.setFileSize(fileSize);
        slice.setImportSourceDir(localSubDir.toString());
        slice.setStorageType(storageAccess.isMinioMode() ? "MINIO" : "LOCAL");
        slice.setStatus("CONVERTING");
        dataSliceMapper.updateById(slice);

        String key = StorageKeys.originalZip(slice.getId());
        try {
            storageAccess.put(key, zipFile, ZIP_CONTENT_TYPE);
        } catch (Exception ex) {
            slice.setStatus("ERROR");
            slice.setErrorMsg("UPLOAD_FAILED: " + ex.getMessage());
            dataSliceMapper.updateById(slice);
            throw new IllegalStateException("MCAP_UPLOAD_FAILED: " + key, ex);
        }
        slice.setFilePath(key);
        slice.setStorageType(storageAccess.isMinioMode() ? "MINIO" : "LOCAL");
        slice.setStatus("READY");
        dataSliceMapper.updateById(slice);

        log.info("Hirain 切片加载完成: id={} vin={} session={} key={} ({} 字节)",
                slice.getId(), vin, sessionId, key, fileSize);

        if (cleanupDownloaded) {
            storageAccess.deleteRecursively(localSubDir);
            storageAccess.deleteRecursively(outputDir);
            try {
                Files.deleteIfExists(zipFile);
            } catch (IOException e) {
                log.warn("删除 Hirain 切片本地 zip 文件失败: {}", zipFile, e);
            }
        }
        return slice;
    }

    /**
     * 用户点击「加载」时调用：按切片记录从 BOS 全量拉取并走完整转换/上传流程。
     *
     * @param sliceId data_slice.id
     */
    public void loadSlice(String sliceId) throws IOException, InterruptedException {
        if (sliceId == null || sliceId.isBlank()) {
            throw new IllegalArgumentException("SLICE_ID_EMPTY");
        }
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new IllegalStateException("SLICE_NOT_FOUND: sliceId=" + sliceId);
        }
        String subDir = slice.getImportBosKey();
        if (subDir == null || subDir.isBlank()) {
            throw new IllegalStateException("HIRAIN_BOS_KEY_MISSING: sliceId=" + sliceId);
        }
        Set<BosObjectSummary> objects = listObjectsByPrefix(subDir);
        if (objects.isEmpty()) {
            throw new IllegalStateException("HIRAIN_OBJECTS_NOT_FOUND: no objects found for subDir=" + subDir);
        }
        processExistingSlice(slice, objects);
    }


    /* ==================== 重新拉取（供 DataSliceController.reimport 调用） ==================== */

    /**
     * 重新处理指定 Hirain 子集目录：恢复本地数据 → 转换 → 打包 → 上传 → 入库。
     *
     * <p>用于「重新拉取」已有切片：本地目录若不存在则从 BOS 恢复，随后完整重跑下载/转换/入库流程，
     * 并返回新切片 ID。处理成功后会将子集目录记入已处理记录，避免定时任务再次处理。</p>
     *
     * @param subDir    子集目录完整前缀（对应 data_slice.import_bos_key）
     * @param sliceName 切片名称（保留原切片名称；为空则使用 sessionId）
     * @return 新创建的切片 UUID
     */
    public String reprocessSubdir(String subDir, String sliceName) throws IOException, InterruptedException {
        if (subDir == null || subDir.isBlank()) {
            throw new IllegalArgumentException("REIMPORT_SOURCE_MISSING: subDir is empty");
        }

        int restored = restoreIfMissing(subDir);
        if (restored > 0) {
            log.info("reimport restore from Hirain BOS: subDir={}, files={}", subDir, restored);
        }

        Set<BosObjectSummary> objects = listObjectsByPrefix(subDir);
        if (objects.isEmpty()) {
            throw new IllegalStateException("HIRAIN_OBJECTS_NOT_FOUND: no objects found for subDir=" + subDir);
        }

        DataSlice slice = processOneSubdir(subDir, objects, sliceName);

        Set<String> processedDirs = loadProcessedSubdirs();
        if (processedDirs.add(subDir)) {
            saveProcessedSubdirs(processedDirs);
            log.info("Hirain 重新拉取完成并已记录: subDir={}, newSliceId={}", subDir, slice.getId());
        } else {
            log.info("Hirain 重新拉取完成: subDir={}, newSliceId={}", subDir, slice.getId());
        }
        return slice.getId();
    }

    /**
     * 列出指定前缀下所有 BOS 对象（分页，排除目录占位符）。
     */
    private Set<BosObjectSummary> listObjectsByPrefix(String prefix) {
        Set<BosObjectSummary> objects = new LinkedHashSet<>();
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
                if (key != null && key.endsWith("/")) {
                    continue;
                }
                objects.add(obj);
            }
            marker = response.isTruncated() ? response.getNextMarker() : null;
        } while (marker != null);
        return objects;
    }

    /**
     * 若 Hirain 本地源目录缺失或为空，则从 BOS 桶按前缀恢复。
     *
     * @return 实际从 BOS 下载的对象数量；本地已存在且有内容时返回 0
     */
    private int restoreIfMissing(String subDir) throws IOException {
        Path localSubDir = resolveLocalPath(subDir + "/");
        if (Files.exists(localSubDir) && Files.isDirectory(localSubDir)) {
            try (Stream<Path> walk = Files.walk(localSubDir, 1)) {
                boolean hasContent = walk.anyMatch(p -> !p.equals(localSubDir));
                if (hasContent) {
                    return 0;
                }
            }
        }
        Set<BosObjectSummary> objects = listObjectsByPrefix(subDir);
        if (objects.isEmpty()) {
            throw new IllegalStateException("HIRAIN_RESTORE_NOT_FOUND: no objects found for subDir=" + subDir);
        }
        downloadSubdir(subDir, objects);
        return objects.size();
    }

    /**
     * 调用 rewrite_record_patched.py 对 record 文件做时间戳对齐。
     *
     * @param originRecord 原始 .record 文件路径
     * @return 对齐后的 .record 路径（原文件同级目录，文件名加 _rewrite 后缀）
     */
    private Path rewriteCyberRecord(Path originRecord) throws IOException, InterruptedException {
        if (pythonExecutor == null) {
            throw new IllegalStateException("PYTHON_EXECUTOR_NOT_AVAILABLE");
        }
        String fileName = originRecord.getFileName().toString();
        Path rewritten = originRecord.resolveSibling(fileName.replaceFirst("\\\\.record$", "_rewrite.record"));
        log.info("Rewrite Hirain record for timestamp alignment: origin={}, rewritten={}", originRecord, rewritten);
        pythonExecutor.executeRaw(
                java.util.List.of("rewrite_record_patched.py", "-r", originRecord.toString()),
                null,
                rewriteTimeoutSec);
        if (!Files.exists(rewritten)) {
            throw new IllegalStateException("REWRITE_RECORD_MISSING: expected " + rewritten);
        }
        return rewritten;
    }

    /* ==================== 转换程序 ==================== */

    /**
     * 转换解压出来的 {@code *.h265}：<b>所有视频合并为一次转换程序调用</b>——
     * {@code --cyber} 传唯一的 {@code *.record}，所有 {@code *.h265} 各跟一个 {@code --video}，
     * 最终只产出一个文件，写入 {@code outputDir/{session}{ext}}。
     *
     * <p>命令形如：</p>
     * <pre>
     * ./ncap-converter --cyber {session}_messages.record \
     *     --video {session}_Cam_FRONT120.h265 \
     *     --video {session}_Cam_FRONT30.h265 \
     *     --video {session}_Cam_REAR.h265 \
     *     --need_distort -o {session}.mcap
     * </pre>
     *
     * <p>找不到 {@code .record} 或没有 {@code .h265} 时（或 input-mode=data_folder），
     * 退化为一次 {@code --data_folder} 整目录转换，输出同样是单个文件。</p>
     *
     * @return 成功产出的文件个数（恒为 1：所有 h265 合并输出一个文件）
     */
    private int convertVideos(Path cyberFile, List<Path> videoFiles, Path dataFolder,
                              Path outputDir, String sessionId) throws IOException, InterruptedException {
        boolean useFileInput = !DATA_FOLDER_MODE.equalsIgnoreCase(converterInputMode)
                && cyberFile != null && !videoFiles.isEmpty();

        Path outputFile = outputDir.resolve(sessionId + normalizeExtension());
        // 视频按文件名排序，保证多次重跑时 --video 顺序稳定（命令可复现、日志可比对）
        List<Path> videos = useFileInput
                ? videoFiles.stream()
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(Collectors.toList())
                : Collections.emptyList();

        if (useFileInput) {
            log.info("开始转换: {} 个 .h265 + record[{}] -> {}",
                    videos.size(), cyberFile.getFileName(), outputFile);
        } else {
            log.info("无 record/h265（或 input-mode=data_folder），整目录单次转换: {} -> {}",
                    dataFolder, outputFile);
        }

        runMcapConverter(cyberFile, videos, dataFolder, outputFile);

        if (!Files.exists(outputFile)) {
            throw new IllegalStateException("MCAP_CONVERT_EMPTY: 转换未产出文件 " + outputFile);
        }
        log.info("转换完成: {} 个 .h265 合并输出 {}", videos.size(), outputFile);
        return 1;
    }

    /**
     * 调用一次 mcap_converter。
     *
     * <p>输入二选一：</p>
     * <ul>
     *   <li>{@code --cyber {*.record} --video {*.h265} [--video {*.h265} ...]}：
     *       record 只有一个，所有 h265 各跟一个 {@code --video}，一次调用全部转换（优先）；</li>
     *   <li>{@code --data_folder {目录}}：找不到 record/h265，或 input-mode=data_folder。</li>
     * </ul>
     *
     * <p>{@code -o} 输出参数放在最后，与手工执行命令的写法保持一致；
     * 标准输出/错误重定向到 {@code {output}.convert.log}，避免大量输出导致进程阻塞。</p>
     *
     * @param videoFiles 本次要转换的全部 h265（可为空，表示走 data_folder 模式）
     */
    private void runMcapConverter(Path cyberFile, List<Path> videoFiles, Path dataFolder, Path outputFile)
            throws IOException, InterruptedException {
        if (mcapConverterPath == null || mcapConverterPath.isBlank()) {
            throw new IllegalStateException(
                    "MCAP_CONVERTER_NOT_CONFIGURED: dims.download.hirain.mcap-converter-path 未配置");
        }
        Files.createDirectories(outputFile.getParent());
        // 重跑时避免读到上一次的残留产物
        Files.deleteIfExists(outputFile);

        List<Path> videos = videoFiles == null ? Collections.emptyList() : videoFiles;
        boolean useFilePair = !DATA_FOLDER_MODE.equalsIgnoreCase(converterInputMode)
                && cyberFile != null && !videos.isEmpty();

        List<String> command = new ArrayList<>();
        command.add(mcapConverterPath);
        if (useFilePair) {
            command.add(CYBER_FLAG);
            command.add(cyberFile.toString());
            // 每个 h265 一个 --video：所有视频一次转换，只产出一个文件
            for (Path video : videos) {
                command.add(VIDEO_FLAG);
                command.add(video.toString());
            }
        } else {
            if (dataFolder == null || !Files.isDirectory(dataFolder)) {
                throw new IllegalStateException("MCAP_CONVERTER_INPUT_MISSING: 未找到 "
                        + converterCyberSuffix + "/" + converterVideoSuffix + " 文件，且无可用输入目录");
            }
            command.add(DATA_FOLDER_FLAG);
            command.add(dataFolder.toString());
        }
//        if (mcapConverterArgs != null && !mcapConverterArgs.isBlank()) {
//            for (String token : mcapConverterArgs.trim().split("\\s+")) {
//                if (!token.isEmpty()) {
//                    command.add(token);
//                }
//            }
//        }
        command.add("--need_distort");
        command.add(OUT_FILE_FLAG);
        command.add(outputFile.toString());

        Path logFile = outputFile.resolveSibling(outputFile.getFileName() + ".convert.log");
        Path workDir = outputFile.getParent();
        log.info("执行转换程序: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());

        Process process = pb.start();
        boolean finished = process.waitFor(mcapConverterTimeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "MCAP_CONVERT_TIMEOUT: 超过 " + mcapConverterTimeoutMinutes + " 分钟，日志 " + logFile);
        }
        int exit = process.exitValue();
        if (exit != 0) {
//            throw new IllegalStateException("MCAP_CONVERT_FAILED: exit=" + exit + "，日志 " + logFile);
        }
        log.info("转换完成: {} -> {}", useFilePair ? cyberFile : dataFolder, outputFile);
    }

    /* ==================== 转换产物打包 ==================== */

    /**
     * 把输出目录下所有转换产物（每个 .h265 转换出来的一个文件）打包成 zip：
     * 默认打包目录下全部文件（{@code bundle-suffix} 可指定只打包某后缀），
     * 一个产物对应 zip 中的一个条目，保留相对路径。
     *
     * @return zip 文件大小（字节）
     */
    private long zipConverted(Path outputPath, Path zipFile) throws IOException {
        List<Path> files = collectBundleFiles(outputPath);
        if (files.isEmpty()) {
            throw new IllegalStateException("MCAP_CONVERT_EMPTY: 转换无产物 " + outputPath);
        }
        Files.createDirectories(zipFile.getParent());
        Files.deleteIfExists(zipFile);
        Path base = Files.isDirectory(outputPath) ? outputPath : outputPath.getParent();
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(base.relativize(file).toString().replace('\\', '/'));
                entry.setSize(Files.size(file));
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
        long size = Files.size(zipFile);
        log.info("转换产物打包完成: {} -> {}（{} 个文件，{} 字节）", outputPath, zipFile, files.size(), size);
        return size;
    }

    /**
     * 清空本地目录（存在则递归删除），用于清理上一次的转换产物。
     */
    private void clearDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            for (int i = paths.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(paths.get(i));
            }
        }
    }

    /**
     * 收集需要打包的转换产物：输出为单文件时直接打包该文件；
     * 输出为目录时按 {@code bundle-suffix} 过滤，过滤结果为空则打包目录下全部文件。
     */
    private List<Path> collectBundleFiles(Path outputPath) throws IOException {
        if (!Files.exists(outputPath)) {
            return Collections.emptyList();
        }
        if (Files.isRegularFile(outputPath)) {
            return List.of(outputPath);
        }
        List<Path> all = findFiles(outputPath, "");
        if (bundleSuffix == null || bundleSuffix.isBlank()) {
            return all;
        }
        List<Path> matched = findFiles(outputPath, bundleSuffix);
        return matched.isEmpty() ? all : matched;
    }

    /* ==================== zip 解压 ==================== */

    /**
     * 解压子集目录下所有 {@code *.zip}：解压到 zip 同级同名目录（去掉 .zip 后缀）。
     * 已解压过（目标目录存在且非空）的 zip 直接跳过，保证重跑幂等。
     *
     * @return 本次得到的解压目录列表（按 zip 名称排序）
     */
    private List<Path> extractZips(Path localSubDir) throws IOException {
        List<Path> zips = findFiles(localSubDir, ZIP_SUFFIX);
        if (zips.isEmpty()) {
            log.info("子集目录[{}]无 zip 包，跳过解压", localSubDir);
            return Collections.emptyList();
        }
        List<Path> extracted = new ArrayList<>();
        for (Path zip : zips) {
            Path target = zip.getParent().resolve(stripZipSuffix(zip.getFileName().toString()));
            if (Files.isDirectory(target) && !isEmptyDir(target)) {
                log.info("zip 已解压，跳过: {} -> {}", zip, target);
                extracted.add(target);
                continue;
            }
            Files.createDirectories(target);
            int count = unzip(zip, target);
            log.info("解压完成: {} -> {}（{} 个文件）", zip, target, count);
            extracted.add(target);
        }
        return extracted;
    }

    /**
     * 解压单个 zip 到目标目录，解析出的条目做路径穿越（zip slip）防护。
     *
     * @return 解压出的文件数量
     */
    private int unzip(Path zipFile, Path targetDir) throws IOException {
        int count = 0;
        try (ZipInputStream zis =
                     new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new IOException("ZIP_SLIP: 非法压缩条目 " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }

    private String stripZipSuffix(String fileName) {
        String name = fileName;
        if (name.toLowerCase().endsWith(ZIP_SUFFIX)) {
            name = name.substring(0, name.length() - ZIP_SUFFIX.length());
        }
        return name;
    }

    private boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }

    /**
     * 在目录下递归查找指定后缀的第一个文件（按路径排序，保证多次运行结果稳定）。
     */
    private Path findFirstFile(Path root, String suffix) throws IOException {
        List<Path> files = findFiles(root, suffix);
        return files.isEmpty() ? null : files.get(0);
    }

    /**
     * 在目录下递归查找指定后缀（忽略大小写）的所有文件，按路径排序。
     */
    private List<Path> findFiles(Path root, String suffix) throws IOException {
        if (root == null || suffix == null || !Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        // 空后缀 = 不过滤，返回目录下全部文件
        String lower = suffix.toLowerCase();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(lower))
                    .forEach(result::add);
        }
        Collections.sort(result);
        return result;
    }

    /* ==================== BOS 扫描与下载 ==================== */

    /**
     * 分页列出 {@code dayPrefix} 前缀下所有对象，按子集目录分组。
     *
     * <p>分组粒度为「{@code {dayPrefix}/{VIN}/{session}_public}」，即日目录下再下沉两级
     * （无下一级时退化为 {@code {dayPrefix}/{VIN}}）。</p>
     *
     * <p>只纳入「上传完成时间距今已超过 {@code uploadLagMinutes} 分钟」的稳定对象。</p>
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
     * 下载子集目录下所有对象到本地（保持目录结构），返回本地子集目录绝对路径。
     */
    private Path downloadSubdir(String subDir, Set<BosObjectSummary> objects) throws IOException {
        Path localSubDir = resolveLocalPath(subDir + "/");
        Files.createDirectories(localSubDir);
        int downloaded = 0;
        for (BosObjectSummary obj : objects) {
            String key = obj.getKey();
            try {
                Path localFile = resolveLocalPath(key);
                if (localFile.getParent() != null) {
                    Files.createDirectories(localFile.getParent());
                }
                // 断点续传：已存在且大小一致则跳过
                if (Files.exists(localFile) && Files.size(localFile) == obj.getSize()) {
                    continue;
                }
                GetObjectRequest getRequest = new GetObjectRequest(bucket, key);
                bosClient.getObject(getRequest, localFile.toFile());
                downloaded++;
            } catch (Exception e) {
                log.error("下载对象失败: {}", key, e);
                throw new IOException("DOWNLOAD_FAILED: " + key, e);
            }
        }
        log.info("子集目录[{}]下载完成：{} 个对象（共 {} 个）-> {}", subDir, downloaded, objects.size(), localSubDir);
        return localSubDir;
    }

    /**
     * 计算「近 {@code recentHours} 小时」处理窗口内涉及的所有日目录前缀
     * （{@code {bosPrefix}/{yyyyMMdd}}，从旧到新排列）。{@code recentHours <= 0} 时仅今天。
     */
    private List<String> recentDayPrefixes(LocalDate today) {
        List<String> prefixes = new ArrayList<>();
        long daysBack = recentHours <= 0 ? 0 : (recentHours + 23) / 24;
        for (long i = daysBack; i >= 0; i--) {
            prefixes.add(buildDayPrefix(today.minusDays(i)));
        }
        return prefixes;
    }

    private String buildDayPrefix(LocalDate day) {
        return normalizePrefix(bosPrefix) + "/" + day.format(DAY_FORMATTER);
    }

    /**
     * 解析对象 key 所属的子集目录：{@code {dayPrefix}/{VIN}/{session}_public}。
     */
    private String resolveSubdir(String key, String dayPrefix) {
        String rel = key;
        if (rel.startsWith(dayPrefix)) {
            rel = rel.substring(dayPrefix.length());
        }
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        if (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }
        String[] parts = rel.split("/");
        // {VIN}/{session}_public/public/{文件} → 取前两级
        if (parts.length >= 3) {
            return dayPrefix + "/" + parts[0] + "/" + parts[1];
        }
        if (parts.length == 2) {
            return dayPrefix + "/" + parts[0];
        }
        return null;
    }

    /**
     * 从子集目录解析 VIN：{@code {bosPrefix}/{yyyyMMdd}/{VIN}/{session}_public} 中的第 3 段。
     */
    private String resolveVin(String subDir) {
        String rel = subDir;
        String prefix = normalizePrefix(bosPrefix);
        if (rel.startsWith(prefix)) {
            rel = rel.substring(prefix.length());
        }
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        String[] parts = rel.split("/");
        // {yyyyMMdd}/{VIN}/{session}_public
        if (parts.length >= 2) {
            return parts[1];
        }
        return parts[0];
    }

    /**
     * 判断对象是否「上传完成时间距今已超过 uploadLagMinutes 分钟」。
     */
    private boolean isMature(BosObjectSummary obj) {
        if (uploadLagMinutes <= 0 || obj.getLastModified() == null) {
            return true;
        }
        Instant lastModified = obj.getLastModified().toInstant();
        return Duration.between(lastModified, Instant.now()).toMinutes() >= uploadLagMinutes;
    }

    /**
     * 把对象 key 映射为本地落地路径（保持目录结构，防止绝对路径/目录穿越）。
     */
    private Path resolveLocalPath(String key) {
        String cleanKey = key.startsWith("/") ? key.substring(1) : key;
        Path relative = Path.of("").resolve(cleanKey).normalize();
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("非法对象 key: " + key);
        }
        return Path.of(localDir).resolve(relative);
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String p = prefix.trim();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /* ==================== 切片与存储 ==================== */

    /**
     * 转换产物统一输出目录：{@code {converted-dir}/{VIN}/{session}}，
     * 每个 .h265 转换后的文件都放这里，最后整体打包成 zip。
     */
    private Path convertedSessionDir(String vin, String sessionId) {
        return convertedSessionRoot(vin, sessionId);
    }

    /**
     * 转换产物最终上传的 zip 包路径：{@code {converted-dir}/{VIN}/{session}.zip}。
     */
    private Path convertedZip(String vin, String sessionId) {
        return convertedSessionRoot(vin, sessionId).resolveSibling(sessionId + ZIP_SUFFIX);
    }

    private Path convertedSessionRoot(String vin, String sessionId) {
        String root = (convertedDir == null || convertedDir.isBlank())
                ? Path.of(localDir).resolve("converted").toString()
                : convertedDir;
        return Path.of(root).resolve(vin).resolve(sessionId);
    }

    private String normalizeExtension() {
        String ext = (outputExtension == null || outputExtension.isBlank()) ? ".mcap" : outputExtension;
        return ext.startsWith(".") ? ext : "." + ext;
    }

    /**
     * 从本地子集目录的 zip 文件名解析触发类型 event_name。
     * 规则：取文件名（去掉 .zip）中从左往右第 4 个下划线之前的字符串。
     * 例：{@code P0_L2_ID_03_20261023081945603_2680-765340.V1.8.V2.1.1.zip}
     * → {@code P0_L2_ID_03}。
     *
     * @param localSubDir 本地子集目录
     * @return 触发类型；无 zip 或格式不符时返回 null
     */
    private String resolveEventName(Path localSubDir) throws IOException {
        Path zip = findFirstFile(localSubDir, ZIP_SUFFIX);
        if (zip == null) {
            return null;
        }
        String fileName = stripZipSuffix(zip.getFileName().toString());
        int idx = -1;
        for (int i = 0; i < 4; i++) {
            idx = fileName.indexOf('_', idx + 1);
            if (idx == -1) {
                log.warn("Hirain zip 文件名无法解析 event_name（下划线不足 4 个）: {}", zip.getFileName());
                return null;
            }
        }
        return fileName.substring(0, idx);
    }

    /**
     * 组装 META_READY 状态的 data_slice 实体（元数据阶段调用）。
     */
    private DataSlice buildMetaSlice(String subDir, String vin, String sessionId, String sliceName, String eventName) {
        DataSlice slice = new DataSlice();
        slice.setName(sliceName != null && !sliceName.isBlank() ? sliceName : sessionId);
        slice.setEventName(eventName);
        slice.setVin(vin);
        slice.setSessionId(sessionId);
        slice.setSourceType(sourceType);
        slice.setStorageType(storageAccess.isMinioMode() ? "MINIO" : "LOCAL");
        // 仅元数据就绪：用户点击「加载」后再走完整下载/转换/上传流程
        slice.setStatus("META_READY");
        // 源侧可追溯信息：BOS 前缀（import_source_dir 在加载阶段回填）
        slice.setImportBosKey(subDir);
        slice.setStartTs(parseSessionTs(sessionId));
        slice.setTriggerTime(slice.getStartTs());
        slice.setVinMd5Id(resolveVinMd5Id(vin));
        return slice;
    }

    /**
     * 从 BOS 对象集合中解析触发类型 event_name。
     * 规则：取第一个 zip 对象文件名（去掉 .zip）中从左往右第 4 个下划线之前的字符串。
     * 例：{@code P0_L2_ID_03_20261023081945603_2680-765340.V1.8.V2.1.1.zip}
     * → {@code P0_L2_ID_03}。
     *
     * @param objects 子集目录下的 BOS 对象集合
     * @return 触发类型；无 zip 或格式不符时返回 null
     */
    private String resolveEventNameFromObjects(Set<BosObjectSummary> objects) {
        return objects.stream()
                .map(BosObjectSummary::getKey)
                .filter(k -> k != null && k.endsWith(ZIP_SUFFIX))
                .findFirst()
                .map(this::extractEventNameFromZipKey)
                .orElse(null);
    }

    private String extractEventNameFromZipKey(String key) {
        String fileName = stripZipSuffix(Path.of(key).getFileName().toString());
        int idx = -1;
        for (int i = 0; i < 4; i++) {
            idx = fileName.indexOf('_', idx + 1);
            if (idx == -1) {
                log.warn("Hirain zip 文件名无法解析 event_name（下划线不足 4 个）: {}", fileName);
                return null;
            }
        }
        return fileName.substring(0, idx);
    }

    /**
     * 计算子集目录下所有 zip 文件的大小之和（单位：字节）。
     *  Hirain 源数据通常以单个 zip 包形式存储，此处返回该 zip 包大小作为 file_size 预览。
     *
     * @param objects 子集目录下的 BOS 对象集合
     * @return zip 文件总大小；无 zip 时返回 0
     */
    private long resolveZipSize(Set<BosObjectSummary> objects) {
        return objects.stream()
                .filter(o -> {
                    String key = o.getKey();
                    return key != null && key.endsWith(ZIP_SUFFIX);
                })
                .mapToLong(BosObjectSummary::getSize)
                .sum();
    }

    /**
     * 按 VIN 反查 vin_md5_mapping.id（无匹配返回 null，不影响入库）。
     */
    private Long resolveVinMd5Id(String vin) {
        if (vin == null || vin.isBlank()) {
            return null;
        }
        try {
            VinMd5Mapping mapping = vinMd5MappingMapper.selectOne(
                    new LambdaQueryWrapper<VinMd5Mapping>().eq(VinMd5Mapping::getVin, vin));
            return mapping == null ? null : mapping.getId();
        } catch (Exception e) {
            log.warn("按 VIN 反查 vin_md5_mapping 失败，vin={}: {}", vin, e.getMessage());
            return null;
        }
    }

    /**
     * 从会话目录名解析数据起始时间：{@code SHF88589_20261013063545722602_public}
     * → {@code 20261013063545}。Hirain 835 触发时间比 BOS 目录命名早 8 小时，因此统一减 8 小时。
     * 解析失败返回 null。
     */
    private LocalDateTime parseSessionTs(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Matcher matcher = SESSION_TS_PATTERN.matcher(sessionId);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDateTime.parse(matcher.group(1), TS_FORMATTER).minusHours(8);
        } catch (DateTimeParseException e) {
            log.debug("会话目录时间解析失败: {}", sessionId);
            return null;
        }
    }

    /* ==================== 已处理子集目录记录 ==================== */

    private Set<String> loadProcessedSubdirs() {
        Path file = recordFilePath();
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        try {
            List<String> list = objectMapper.readValue(file.toFile(), new TypeReference<List<String>>() {
            });
            return new LinkedHashSet<>(list);
        } catch (IOException e) {
            log.warn("读取 Hirain 处理记录失败，按空记录处理: {}", file, e);
            return new LinkedHashSet<>();
        }
    }

    private void saveProcessedSubdirs(Collection<String> subdirs) {
        try {
            Path file = recordFilePath();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            List<String> ordered = new ArrayList<>(subdirs);
            Collections.sort(ordered);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), ordered);
        } catch (IOException e) {
            log.error("写入 Hirain 处理记录失败: {}", recordFilePath(), e);
        }
    }

    private Path recordFilePath() {
        if (recordFile == null || recordFile.isBlank()) {
            return Path.of(localDir).resolve("download-hirain-subdirs.json");
        }
        return Path.of(recordFile);
    }
}
