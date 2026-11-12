package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.config.StorageConfig;
import com.isim.dims.dto.DirectoryImportRequest;
import com.isim.dims.dto.DirectoryImportResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.IngestionTaskStatusHistory;
import com.isim.dims.entity.VinMd5Mapping;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.IngestionTaskStatusHistoryMapper;
import com.isim.dims.mapper.VinMd5MappingMapper;
import com.isim.dims.service.FisheyePreviewService;
import com.isim.dims.service.LocalDirectoryImportPipelineService;
import com.isim.dims.service.LocalDirectoryImportService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地目录导入 Service 实现
 * <p>
 * 同步阶段（本类）：扫描目录 → MD5 子目录 → vin_md5_mapping 反查 VIN →
 * 会话目录枚举 → 去重/校验 → 创建 DataSlice + IngestionTask + 首条状态历史。
 * 目录级失败（DIRECTORY_NOT_FOUND / DIRECTORY_NOT_DIRECTORY /
 * DIRECTORY_NOT_READABLE / EMPTY_DIRECTORY）与 strict 无关，始终抛 400；
 * strict=true 时任一校验失败统一抛 VALIDATION_FAILED（原因写 message）；
 * strict=false 时跳过失败会话继续（skipped 记录原因）。
 * 全部记录创建成功后，通过事务 afterCommit 回调将导入清单委派给
 * {@link LocalDirectoryImportPipelineService} 异步执行转换流水线
 * （消除 @Transactional + @Async 竞态）。
 *
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalDirectoryImportServiceImpl implements LocalDirectoryImportService {

    private final VinMd5MappingMapper vinMd5MappingMapper;
    private final DataSliceMapper dataSliceMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionTaskStatusHistoryMapper ingestionTaskStatusHistoryMapper;
    private final StorageConfig storageConfig;
    private final StorageAccess storageAccess;
    private final LocalDirectoryImportPipelineService localDirectoryImportPipelineService;
    private final FisheyePreviewService fisheyePreviewService;
    private final RedissonClient redissonClient;

    /**
     * MD5 子目录名：32 位小写 hex
     */
    private static final Pattern MD5_DIR_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    /**
     * 数据来源提供方白名单（与 DataSliceController.ALLOWED_SOURCES 保持一致）
     */
    private static final Set<String> ALLOWED_SOURCES = Set.of("MMT", "ZYT", "Hirain");

    /**
     * 数据浏览器命名固定前缀（江铃汽车 JMC）
     */
    private static final String PREFIX = "JMC";

    /**
     * 数据浏览器命名：项目名称缺省值
     */
    private static final String DEFAULT_PROJECT_NAME = "CX835ICA";

    /**
     * 数据浏览器命名：录制工具缺省值
     */
    private static final String DEFAULT_RECORDING_TOOL = "CFDI";

    /**
     * 数据浏览器命名：触发类型缺省值
     */
    private static final String DEFAULT_TRIGGER_TYPE = "disabled";

    /**
     * 并发任务数上限：最多允许 30 个处于活跃导入状态的数据切片
     */
    private static final int MAX_CONCURRENT_TASKS = 30;

    /**
     * 分布式锁 Key：序列化并发的目录导入请求，防止 TOCTOU 竞态
     */
    private static final String CONCURRENCY_LOCK_KEY = "lock:data-slice-concurrent-tasks";

    /**
     * 数据浏览器命名：算法供应商兜底值（正常情况下 source 必填，此处防御）
     */
    private static final String DEFAULT_SUPPLIER = "UNKNOWN";

    /**
     * 数据浏览器命名中「录制时间」的输出格式：yyyy_MM_dd_HH_mm_ss（下划线分隔，便于作为文件名/目录名使用）
     */
    private static final DateTimeFormatter NAME_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");

    /**
     * 命名中「录制时间」的时区偏移（小时）：由 UTC 换算为东八区（北京时间）。
     *
     * <p>仅作用于切片命名；data_slice.trigger_time / start_ts / end_ts 仍统一落库 UTC，
     * 排序、比较与 MXF 时间筛选口径不变。</p>
     */
    private static final long RECORD_TIME_ZONE_OFFSET_HOURS = 8;

    /**
     * JSON 解析器
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 会话目录名：{md5后8位}_{时间戳数字}_public
     * 时间戳位数 14-20（覆盖 17 位规范与示例的 20 位 yyyyMMddHHmmssSSSSSS）
     */
    private static final Pattern SESSION_DIR_PATTERN = Pattern.compile("^([0-9a-f]{8})_([0-9]{14,20})_public$");

    @Override
    @Transactional
    public DirectoryImportResponse importDirectory(DirectoryImportRequest request) {
        // 分布式锁：序列化并发的目录导入请求，防止 TOCTOU 竞态
        // （ READ_COMMITTED 下，另一事务的插入在未提交前对本事务不可见，
        //   因此 selectCount + insert 存在先验竞态：多请求同时通过检查）
        RLock lock = redissonClient.getLock(CONCURRENCY_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(10, 300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (!locked) {
            throw new RuntimeException("MAX_CONCURRENT_TASKS_LOCKED: another directory import is in progress, please retry");
        }

        // 在事务提交/回滚后释放锁，确保后续请求能看到本次插入的记录
        boolean syncActive = TransactionSynchronizationManager.isSynchronizationActive();
        if (syncActive) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    lock.unlock();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        lock.unlock();
                    }
                }
            });
        }

        try {
            // 只允许 MAX_CONCURRENT_TASKS 个并发任务
            long concurrentTasks = countConcurrentTasks();
            if (concurrentTasks >= MAX_CONCURRENT_TASKS) {
                throw new RuntimeException("MAX_CONCURRENT_TASKS_EXCEEDED: Only " + MAX_CONCURRENT_TASKS + " concurrent tasks allowed");
            }

            String jobId = UUID.randomUUID().toString();
            DirectoryImportResponse response = new DirectoryImportResponse();
            response.setJobId(jobId);
            response.setScanned(0);
            response.setTotalSessions(0);
            response.setImported(new ArrayList<>());
            response.setSkipped(new ArrayList<>());

            // 1. 校验数据来源提供方（与目录级错误同为硬错误，不随 strict 变化）
            if (request.getSource() == null || request.getSource().isBlank()
                    || !ALLOWED_SOURCES.contains(request.getSource())) {
                throw new RuntimeException("VALIDATION_FAILED: source must be one of MMT/ZYT/Hirain");
            }

            // 2. 校验目录存在/可读（目录级失败始终抛出，不随 strict 变化）
            String requestedDir = request.getDirectory();
            if (requestedDir == null || requestedDir.isBlank()) {
                throw new RuntimeException("DIRECTORY_NOT_FOUND: directory is required");
            }
            Path baseDir;
            try {
                baseDir = Paths.get(requestedDir).toRealPath();
            } catch (IOException e) {
                log.warn("Directory not found or unreadable: {}", requestedDir);
                throw new RuntimeException("DIRECTORY_NOT_FOUND: " + requestedDir, e);
            }
            if (!Files.isDirectory(baseDir)) {
                throw new RuntimeException("DIRECTORY_NOT_DIRECTORY: not a directory: " + baseDir);
            }
            if (!Files.isReadable(baseDir)) {
                throw new RuntimeException("DIRECTORY_NOT_READABLE: not readable: " + baseDir);
            }
            response.setRequestedDir(baseDir.toString());
            log.info("Directory import started: jobId={}, dir={}, strict={}", jobId, baseDir, request.isStrict());

            // 2. 扫描一级子目录，过滤 MD5 子目录
//        List<Path> md5Dirs = listImmediateDirs(baseDir);
            List<Path> md5Dirs = Arrays.asList(baseDir);
            List<Path> md5Matched = md5Dirs.stream()
                    .filter(p -> MD5_DIR_PATTERN.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
            response.setScanned(md5Matched.size());
            log.info("Directory import scan: jobId={}, total subdirs={}, md5 dirs={}", jobId, md5Dirs.size(), md5Matched.size());

            // 无任何有效 MD5 子目录 → EMPTY_DIRECTORY（与 strict 无关，目录级硬错误）
            if (md5Matched.isEmpty()) {
                throw new RuntimeException("EMPTY_DIRECTORY: no MD5 subdirectories found in " + baseDir);
            }

            List<LocalDirectoryImportPipelineService.ImportedSession> importedSessions = new ArrayList<>();

            // 3. 逐个 MD5 目录处理
            for (Path md5Dir : md5Matched) {
                String md5 = md5Dir.getFileName().toString();

                // 3a. vin_md5_mapping 反查 VIN
                VinMd5Mapping mapping = vinMd5MappingMapper.selectOne(
                        new LambdaQueryWrapper<VinMd5Mapping>().eq(VinMd5Mapping::getMd5, md5)
                );

                if (mapping == null) {
                    log.warn("MD5 not mapped to any VIN: md5={}", md5);
                    if (request.isStrict()) {
                        throw new RuntimeException("VALIDATION_FAILED: MD5_NOT_MAPPED: no vin_md5_mapping record for md5=" + md5);
                    }
                    addSkipped(response, md5, null, "MD5_NOT_MAPPED");
                    continue;
                }

                // 3b. 枚举会话目录（{md5后8位}_{时间戳}_public，前缀须与 MD5 末 8 位一致）
                List<Path> sessionDirs = new ArrayList<>();
                for (Path child : listImmediateDirs(md5Dir)) {
                    String fileName = child.getFileName().toString();
                    Matcher m = SESSION_DIR_PATTERN.matcher(fileName);
                    if (!m.matches()) {
                        continue; // 非会话目录（如说明文件），忽略不计入
                    }
                    String md5Suffix = md5.toLowerCase(Locale.ROOT).substring(md5.length() - 8);
                    if (!m.group(1).equals(md5Suffix)) {
                        log.warn("Session dir prefix mismatch: dir={}, md5={}", fileName, md5);
                        if (request.isStrict()) {
                            throw new RuntimeException("VALIDATION_FAILED: INVALID_SESSION: prefix of " + fileName + " != md5 suffix " + md5Suffix);
                        }
                        addSkipped(response, md5, fileName, "INVALID_SESSION");
                        continue;
                    }
                    sessionDirs.add(child);
                }
                sessionDirs.sort(Comparator.comparing(p -> p.getFileName().toString()));

                // 3c. 逐个会话处理
                for (Path sessionDir : sessionDirs) {
                    String sessionId = sessionDir.getFileName().toString();
                    if (!handleSession(request, response, importedSessions, md5, mapping, sessionDir, sessionId, jobId)) {
                        continue; // 已跳过（原因已记录）
                    }
                }
            }

            // 4. 汇总并在事务提交后派发异步流水线
            // 事务内直接调用 @Async 方法存在竞态（流水线侧 waitForTask 轮询兜底），
            // 改为注册 afterCommit 回调：事务提交后记录可见，消除 UPLOADING 僵尸态
            response.setTotalSessions(response.getImported().size() + response.getSkipped().size());
            if (!importedSessions.isEmpty()) {
                log.info("Directory import registering afterCommit dispatch: jobId={}, sessions={}", jobId, importedSessions.size());
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
//                        localDirectoryImportPipelineService.processImport(importedSessions);
                            importedSessions.forEach(session -> localDirectoryImportPipelineService.processOneImport(session));
                        }
                    });
                } else {
                    // 无事务上下文（如单元测试直连 Service）时退化为直接派发
//                localDirectoryImportPipelineService.processImport(importedSessions);
                    importedSessions.forEach(session -> localDirectoryImportPipelineService.processOneImport(session));
                }
            } else {
                log.info("Directory import nothing to process: jobId={}", jobId);
            }
            return response;
        } finally {
            // 非事务上下文（如单元测试直连 Service）时立即释放锁
            if (!syncActive) {
                lock.unlock();
            }
        }
    }

    /**
     * meta-only 导入（MMT BOS 调度专用）
     *
     * <p>调度只下载 meta + 前视鱼眼 h265，本方法解析 meta 建切片
     * （status=META_READY，含事件统计元信息），不校验 mfbag、不建
     * IngestionTask、不跑转换流水线；同时记录 import_bos_key 供后续
     * 按需全量拉取，并异步生成前视鱼眼预览 mp4。</p>
     */
    @Override
    @Transactional
    public DirectoryImportResponse importDirectoryMetaOnly(DirectoryImportRequest request) {
        // 只允许 MAX_CONCURRENT_TASKS 个并发任务
        long currentConcurrent = countConcurrentTasks();
        if (currentConcurrent >= MAX_CONCURRENT_TASKS) {
            if (request.isStrict()) {
                throw new RuntimeException("MAX_CONCURRENT_TASKS_EXCEEDED: Only " + MAX_CONCURRENT_TASKS + " concurrent tasks allowed");
            }
        }

        String jobId = UUID.randomUUID().toString();
        DirectoryImportResponse response = new DirectoryImportResponse();
        response.setJobId(jobId);
        response.setScanned(0);
        response.setTotalSessions(0);
        response.setImported(new ArrayList<>());
        response.setSkipped(new ArrayList<>());

        if (request.getSource() == null || request.getSource().isBlank()
                || !ALLOWED_SOURCES.contains(request.getSource())) {
            throw new RuntimeException("VALIDATION_FAILED: source must be one of " + ALLOWED_SOURCES);
        }
        if (request.getBosKeyPrefix() == null || request.getBosKeyPrefix().isBlank()) {
            throw new RuntimeException("VALIDATION_FAILED: bosKeyPrefix is required for meta-only import");
        }
        String requestedDir = request.getDirectory();
        if (requestedDir == null || requestedDir.isBlank()) {
            throw new RuntimeException("DIRECTORY_NOT_FOUND: directory is required");
        }
        Path baseDir;
        try {
            baseDir = Paths.get(requestedDir).toRealPath();
        } catch (IOException e) {
            log.warn("Directory not found or unreadable: {}", requestedDir);
            throw new RuntimeException("DIRECTORY_NOT_FOUND: " + requestedDir, e);
        }
        if (!Files.isDirectory(baseDir) || !Files.isReadable(baseDir)) {
            throw new RuntimeException("DIRECTORY_NOT_DIRECTORY: not a readable directory: " + baseDir);
        }
        response.setRequestedDir(baseDir.toString());
        log.info("Meta-only directory import started: jobId={}, dir={}, bosKeyPrefix={}",
                jobId, baseDir, request.getBosKeyPrefix());

        // 与 importDirectory 一致：baseDir 本身即 MD5 子目录（BOS 子集目录 = {prefix}/{yyyyMMdd}/{md5}）
        List<Path> md5Matched = List.of(baseDir).stream()
                .filter(p -> MD5_DIR_PATTERN.matcher(p.getFileName().toString()).matches())
                .collect(Collectors.toList());
        response.setScanned(md5Matched.size());
        if (md5Matched.isEmpty()) {
            throw new RuntimeException("EMPTY_DIRECTORY: no MD5 subdirectories found in " + baseDir);
        }

        List<Runnable> previewJobs = new ArrayList<>();
        for (Path md5Dir : md5Matched) {
            String md5 = md5Dir.getFileName().toString();

            VinMd5Mapping mapping = vinMd5MappingMapper.selectOne(
                    new LambdaQueryWrapper<VinMd5Mapping>().eq(VinMd5Mapping::getMd5, md5));
            if (mapping == null) {
                log.warn("Meta-only import: MD5 not mapped to any VIN: md5={}", md5);
                mapping = new VinMd5Mapping();
                mapping.setVin(md5);
                mapping.setId(1L);
                mapping.setMd5(md5);
//                addSkipped(response, md5, null, "MD5_NOT_MAPPED");
//                continue;
            }

            for (Path child : listImmediateDirs(md5Dir)) {
                String sessionId = child.getFileName().toString();
                Matcher m = SESSION_DIR_PATTERN.matcher(sessionId);
                if (!m.matches()) {
                    continue; // 非会话目录，忽略不计入
                }
                String md5Suffix = md5.toLowerCase(Locale.ROOT).substring(md5.length() - 8);
                if (!m.group(1).equals(md5Suffix)) {
                    log.warn("Meta-only import: session dir prefix mismatch: dir={}, md5={}", sessionId, md5);
                    addSkipped(response, md5, sessionId, "INVALID_SESSION");
                    continue;
                }
                handleSessionMetaOnly(request, response, previewJobs, md5, mapping, child, jobId);
            }
        }
        response.setTotalSessions(response.getImported().size() + response.getSkipped().size());
        log.info("Meta-only directory import completed: jobId={}, imported={}, skipped={}",
                jobId, response.getImported().size(), response.getSkipped().size());

        // 切片落库提交后再异步转码鱼眼预览，避免记录未提交时预览已生成
        if (!previewJobs.isEmpty()) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        previewJobs.forEach(Runnable::run);
                    }
                });
            } else {
                previewJobs.forEach(Runnable::run);
            }
        }
        return response;
    }

    private void handleSessionMetaOnly(DirectoryImportRequest request,
                                       DirectoryImportResponse response,
                                       List<Runnable> previewJobs,
                                       String md5,
                                       VinMd5Mapping mapping,
                                       Path sessionDir,
                                       String jobId) {
        String sessionId = sessionDir.getFileName().toString();
        Long duplicateCount = dataSliceMapper.selectCount(
                new LambdaQueryWrapper<DataSlice>()
                        .eq(DataSlice::getImportMd5, md5)
                        .eq(DataSlice::getSessionId, sessionId));
        if (duplicateCount != null && duplicateCount > 0) {
            addSkipped(response, md5, sessionId, "ALREADY_IMPORTED");
            return;
        }

        // 扫描会话目录：定位 meta 文件，统计本地已下载文件字节数
        var ref = new Object() {
            MetaInfo metaInfo = null;
        };
        long[] localBytes = {0L};
        if (Files.isDirectory(sessionDir) && Files.isReadable(sessionDir)) {
            try (Stream<Path> stream = Files.walk(sessionDir)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String fileName = p.getFileName().toString();
                    try {
                        localBytes[0] += Files.size(p);
                        if (fileName.equals("meta")) {
                            ref.metaInfo = parseMetaJson(Files.readString(p));
                        }
                    } catch (IOException e) {
                        log.warn("Failed to read file {}: {}", p, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("Failed to walk session dir {}: {}", sessionDir, e.getMessage());
            }
        }
        if (ref.metaInfo == null) {
            log.warn("Meta-only import: no meta file under {}: md5={}, sessionId={}", sessionDir, md5, sessionId);
            addSkipped(response, md5, sessionId, "META_NOT_FOUND");
            return;
        }

        Matcher m = SESSION_DIR_PATTERN.matcher(sessionId);
        if (!m.matches()) {
            addSkipped(response, md5, sessionId, "INVALID_SESSION");
            return;
        }
        String ts = m.group(2);

        String sliceId = UUID.randomUUID().toString();
        DataSlice dataSlice = new DataSlice();
        dataSlice.setId(sliceId);
        dataSlice.setName(buildSliceName(request, mapping, md5, ts, ref.metaInfo));
        dataSlice.setEventName(ref.metaInfo.getEventName());
        dataSlice.setVehicleType(ref.metaInfo.getCarType());
        dataSlice.setSystemName(ref.metaInfo.getSystemName());
        dataSlice.setTriggerTime(ref.metaInfo.getTriggerTimeUtc());
        dataSlice.setFilePath(sessionDir.toString());
        // 当前仅含 meta + 前视鱼眼，全量加载后由导入流水线回写真实大小
        dataSlice.setFileSize(localBytes[0]);
        dataSlice.setStatus("META_READY");
        dataSlice.setSourceType(request.getSource());
        dataSlice.setStorageType(storageAccess.isMinioMode() ? "MINIO" : "LOCAL");
        dataSlice.setVinMd5Id(mapping.getId());
        dataSlice.setVin(mapping.getVin());
        dataSlice.setImportMd5(md5);
        dataSlice.setSessionId(sessionId);
        dataSlice.setImportSourceDir(sessionDir.toAbsolutePath().toString());
        dataSlice.setImportBosKey(joinBosKey(request.getBosKeyPrefix(), sessionId));
        try {
            dataSliceMapper.insert(dataSlice);
        } catch (DuplicateKeyException e) {
            addSkipped(response, md5, sessionId, "ALREADY_IMPORTED");
            return;
        }

        DirectoryImportResponse.ImportedItem item = new DirectoryImportResponse.ImportedItem();
        item.setSliceId(sliceId);
        item.setTaskId(null);
        item.setMd5(md5);
        item.setSessionId(sessionId);
        item.setVin(mapping.getVin());
        item.setVehicleNo(mapping.getVehicleNo());
        item.setStatus("META_READY");
        response.getImported().add(item);

        String sessionDirPath = sessionDir.toAbsolutePath().toString();
        previewJobs.add(() -> fisheyePreviewService.generatePreviewAsync(sliceId, sessionDirPath));
        log.info("Session meta-only imported: jobId={}, sliceId={}, md5={}, sessionId={}, vin={}, localBytes={}",
                jobId, sliceId, md5, sessionId, mapping.getVin(), localBytes[0]);
    }

    private static String joinBosKey(String prefix, String sessionId) {
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return p + "/" + sessionId;
    }

    /**
     * 查询当前活跃的并发任务数。
     * <p>
     * 活跃状态 = 处于 UPLOADING / CONVERTING / PARSING / TAGGING / VIDEO_EXTRACTING / SCENE_EXTRACTING
     * 任一状态，且在最近 25 分钟内创建。
     * <p>
     * 在同一事务内多次调用时，selectCount 复用同一数据库连接，
     * 因此能看到本事务中已插入的新记录（未提交也可见），
     * 配合分布式锁一起防止 TOCTOU 竞态。
     */
    public long countConcurrentTasks() {
        return dataSliceMapper.selectCount(
                new LambdaQueryWrapper<DataSlice>()
                        .in(DataSlice::getStatus,
                                "LOADING", "UPLOADING", "CONVERTING", "PARSING",
                                "TAGGING", "VIDEO_EXTRACTING", "SCENE_EXTRACTING")
                        .ge(DataSlice::getCreatedAt, LocalDateTime.now().minusHours(8).minusMinutes(25)));// UTC+8
    }

    /**
     * 处理单个会话目录：去重 → mfbag 校验 → 建记录
     *
     * @return true=已导入，false=已跳过（跳过原因已写入 response.skipped）
     */
    private boolean handleSession(DirectoryImportRequest request,
                                  DirectoryImportResponse response,
                                  List<LocalDirectoryImportPipelineService.ImportedSession> importedSessions,
                                  String md5,
                                  VinMd5Mapping mapping,
                                  Path sessionDir,
                                  String sessionId,
                                  String jobId) {
        // 4. 去重检查：同一 (import_md5, session_id) 只允许导入一次
        Long duplicateCount = dataSliceMapper.selectCount(
                new LambdaQueryWrapper<DataSlice>()
                        .eq(DataSlice::getImportMd5, md5)
                        .eq(DataSlice::getSessionId, sessionId)
        );
        if (duplicateCount != null && duplicateCount > 0) {
            log.warn("Session already imported: md5={}, sessionId={}", md5, sessionId);
            if (request.isStrict()) {
                throw new RuntimeException("VALIDATION_FAILED: ALREADY_IMPORTED: md5=" + md5 + ", sessionId=" + sessionId);
            }
            addSkipped(response, md5, sessionId, "ALREADY_IMPORTED");
            return false;
        }

        // 5. 校验 {session} 递归扫描下存在 ≥1 个 .mfbag 文件，并统计字节和
        Path mfbagDir = sessionDir;
        List<Path> mfbagFiles = new ArrayList<>();
        var ref = new Object() {
            MetaInfo metaInfo = null;
        };

        long[] fileSize = {0L};
        if (Files.isDirectory(sessionDir) && Files.isReadable(sessionDir)) {
            try (Stream<Path> stream = Files.walk(sessionDir)) {
                stream.filter(Files::isRegularFile)
                        .forEach(p -> {
                            String fileName = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (fileName.endsWith(".mfbag")) {
                                mfbagFiles.add(p);
                                try {
                                    fileSize[0] += Files.size(p);
                                } catch (IOException e) {
                                    log.warn("Failed to stat mfbag file {}: {}", p, e.getMessage());
                                }
                            }
                            if (fileName.equals("meta")) {
                                try {
                                    String content = Files.readString(p);
                                    log.debug("meta content: {}", content);
                                    ref.metaInfo = parseMetaJson(content);
                                } catch (IOException e) {
                                    log.warn("Failed to read meta file {}: {}", p, e.getMessage());
                                }
                            }
                        });
            } catch (IOException e) {
                log.warn("Failed to list mfbag dir {}: {}", mfbagDir, e.getMessage());
            }
        }
        if (mfbagFiles.isEmpty()) {
            log.warn("No .mfbag files under {}: md5={}, sessionId={}", mfbagDir, md5, sessionId);
            if (request.isStrict()) {
                throw new RuntimeException("VALIDATION_FAILED: MFBAG_NOT_FOUND: no .mfbag under " + mfbagDir);
            }
            addSkipped(response, md5, sessionId, "MFBAG_NOT_FOUND");
            return false;
        }

        // 6. 创建 DataSlice 记录（source_type=前端传参的数据来源提供方；uk_import_session 唯一键兜底并发去重）
        String sliceId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        Matcher tsMatcher = SESSION_DIR_PATTERN.matcher(sessionId);
        if (!tsMatcher.matches()) {
            throw new RuntimeException("VALIDATION_FAILED: INVALID_SESSION: unexpected session dir name " + sessionId);
        }
        String ts = tsMatcher.group(2);

        DataSlice dataSlice = new DataSlice();
        dataSlice.setId(sliceId);
        dataSlice.setName(buildSliceName(request, mapping, md5, ts, ref.metaInfo));
        dataSlice.setEventName(ref.metaInfo.getEventName());
        dataSlice.setVehicleType(ref.metaInfo.getCarType());
        dataSlice.setSystemName(ref.metaInfo.getSystemName());
        // 触发时间（V24 新增列）：meta.trigger_timestamp_us + utc_diff_us = 事件真实 UTC 触发时刻，
        // 落库存 UTC（与 start_ts/end_ts 语义一致），前端按东八区（北京时间）展示
        dataSlice.setTriggerTime(ref.metaInfo.getTriggerTimeUtc());
        dataSlice.setFilePath(sessionDir.toString()); // 占位：会话目录绝对路径，转换完成后回写 bag 路径
        dataSlice.setFileSize(fileSize[0]);
        dataSlice.setStatus("UPLOADING");
        dataSlice.setSourceType(request.getSource());
        // 存储类型随当前系统模式标记：minio 模式下新 slice 必须为 MINIO，
        // 否则流水线自身的数据访问（上传后读取/解析）会被 DATA_ARCHIVED 拒绝
        dataSlice.setStorageType(storageAccess.isMinioMode() ? "MINIO" : "LOCAL");
        dataSlice.setVinMd5Id(mapping.getId());
        dataSlice.setVin(mapping.getVin());
        dataSlice.setImportMd5(md5);
        dataSlice.setSessionId(sessionId);
        dataSlice.setImportSourceDir(sessionDir.toAbsolutePath().toString());
        // 防并发越界：单次 importDirectory 可能创建多条 DataSlice，
        // 在分布式锁保护下，selectCount 能见到本事务已插入的记录，
        // 故此处可安全校验 —— 当活跃任务数达上限时，跳过后续会话
        long currentConcurrent = countConcurrentTasks();
        if (currentConcurrent >= MAX_CONCURRENT_TASKS) {
            if (request.isStrict()) {
                throw new RuntimeException("MAX_CONCURRENT_TASKS_EXCEEDED: Only " + MAX_CONCURRENT_TASKS + " concurrent tasks allowed (md5=" + md5 + ", sessionId=" + sessionId + ")");
            }
            log.warn("Concurrent task limit reached ({}): md5={}, sessionId={}, skipping", currentConcurrent, md5, sessionId);
            addSkipped(response, md5, sessionId, "MAX_CONCURRENT_TASKS");
            return false;
        }
        try {
            dataSliceMapper.insert(dataSlice);
        } catch (DuplicateKeyException e) {
            // 并发重复导入：另一请求已插入同一 (import_md5, session_id)（uk_import_session 冲突）
            log.warn("Duplicate import caught by uk_import_session: md5={}, sessionId={}", md5, sessionId);
            if (request.isStrict()) {
                throw new RuntimeException("VALIDATION_FAILED: ALREADY_IMPORTED: md5=" + md5 + ", sessionId=" + sessionId, e);
            }
            addSkipped(response, md5, sessionId, "ALREADY_IMPORTED");
            return false;
        }

        // 7. 创建 IngestionTask + 首条状态历史
        IngestionTask task = new IngestionTask();
        task.setId(taskId);
        task.setSliceId(sliceId);
        task.setStatus("UPLOADING");
        task.setProgress(0);
        ingestionTaskMapper.insert(task);

        IngestionTaskStatusHistory history = new IngestionTaskStatusHistory();
        history.setTaskId(taskId);
        history.setStatus("UPLOADING");
        history.setProgress(0);
        ingestionTaskStatusHistoryMapper.insert(history);

        // 8. 记录到 imported 列表与流水线清单
        DirectoryImportResponse.ImportedItem item = new DirectoryImportResponse.ImportedItem();
        item.setSliceId(sliceId);
        item.setTaskId(taskId);
        item.setMd5(md5);
        item.setSessionId(sessionId);
        item.setVin(mapping.getVin());
        item.setVehicleNo(mapping.getVehicleNo());
        item.setStatus("UPLOADING");
        response.getImported().add(item);

        importedSessions.add(new LocalDirectoryImportPipelineService.ImportedSession(
                sliceId, taskId, sessionDir.toAbsolutePath().toString(),
                md5, sessionId, mapping.getVin(), mapping.getVehicleNo()));

        log.info("Session imported: jobId={}, sliceId={}, taskId={}, md5={}, sessionId={}, vin={}, mfbagFiles={}, size={}",
                jobId, sliceId, taskId, md5, sessionId, mapping.getVin(), mfbagFiles.size(), fileSize);
        return true;
    }

    /**
     * 切片名称：{JMC}_{项目名称}_{VIN号}_{算法供应商}_{录制工具}_{触发类型}_{录制时间}，数据浏览器命名规范。
     * (项目名称:meta里"car_type"字段, 优先使用meta字段, 回退到request.projectName, 再到默认CX835ICA;
     * 录制工具:meta里"collect_type"字段, 优先使用meta字段, 回退到request.recordingTool, 再到默认CFDI;
     * 触发类型:meta里"event_name"字段, 优先使用meta字段, 回退到request.triggerType, 再到默认disabled)
     * <ul>
     *   <li>JMC：固定前缀（江铃汽车）</li>
     *   <li>项目名称：meta.car_type 优先 > request.projectName（缺省 CX835ICA）</li>
     *   <li>VIN号：vin_md5_mapping.vin，缺失时回退 vehicle_no / model_code / md5 保证可追溯</li>
     *   <li>车源（算法供应商）：request.source（MMT|ZYT|Hirain）</li>
     *   <li>录制工具：meta.collect_type 优先 > request.recordingTool（缺省 CFDI）</li>
     *   <li>触发类型：meta.event_name 优先 > request.triggerType（缺省 disabled）</li>
     *   <li>录制时间：meta.trigger_timestamp_us + meta.utc_diff_us（事件真实触发时刻，UTC）
     *       +8 小时换算为东八区（北京时间），格式化为 yyyy_MM_dd_HH_mm_ss；
     *       meta 缺失/字段非法时回退会话目录名时间戳 ts（已是本地时间，不再加偏移）</li>
     * </ul>
     * 命名示例：JMC_CX835ICA_LXWHEKDC7THG05069_MMT_CFDI_disabled_2026_07_09_14_38_44
     */
    private String buildSliceName(DirectoryImportRequest request, VinMd5Mapping mapping, String md5, String ts, MetaInfo metaInfo) {
        log.debug("meta contentRes: {}", metaInfo);
        String project = defaultIfBlank(metaInfo != null ? metaInfo.getCarType() : null,
                defaultIfBlank(request.getProjectName(), DEFAULT_PROJECT_NAME));
        String source = defaultIfBlank(request.getSource(), DEFAULT_SUPPLIER);

        String tool = defaultIfBlank(metaInfo != null ? metaInfo.getCollectType() : null,
                defaultIfBlank(request.getRecordingTool(), DEFAULT_RECORDING_TOOL));

        String trigger = defaultIfBlank(metaInfo != null ? metaInfo.getEventName() : null,
                defaultIfBlank(request.getTriggerType(), DEFAULT_TRIGGER_TYPE));

        String vin = resolveVin(mapping, md5);
        return PREFIX + "_" + project + "_" + vin + "_" + source + "_" + tool + "_" + trigger
                + "_" + formatRecordTime(metaInfo, ts);
    }

    /**
     * 命名中的「录制时间」：优先取 meta 触发时刻
     * {@code meta.trigger_timestamp_us + meta.utc_diff_us}（即 {@link MetaInfo#getTriggerTimeUtc()}，
     * 事件真实触发时刻，UTC）+8 小时（东八区/北京时间）后输出 {@code yyyy_MM_dd_HH_mm_ss}
     * （下划线分隔，避免冒号/空格导致导出目录名在 Windows 下非法）。
     * <p>
     * meta 缺失或字段非法（结果为 null）时回退会话目录名解析的时间戳 ts，保证命名可用。
     */
    private String formatRecordTime(MetaInfo metaInfo, String ts) {
        if (metaInfo != null && metaInfo.getTriggerTimeUtc() != null) {
            // 录制时间按东八区（北京时间 UTC+8）输出，与采集端/前端展示口径一致
            return NAME_TIME_FORMAT.format(
                    metaInfo.getTriggerTimeUtc().plusHours(RECORD_TIME_ZONE_OFFSET_HOURS));
        }
        log.warn("meta trigger_timestamp_us/utc_diff_us unavailable, fall back to session dir ts={}", ts);
        return formatTs(ts);
    }

    /**
     * 将会话目录名解析出的数字时间戳（yyyyMMddHHmmss，可带毫秒/微秒后缀）格式化为 {@code yyyy_MM_dd_HH_mm_ss}。
     * 解析失败或格式异常时原样返回，保证命名可用。
     */
    private String formatTs(String ts) {
        if (ts == null || ts.isBlank() || ts.length() < 14) {
            return ts == null ? "" : ts;
        }
        try {
            return NAME_TIME_FORMAT.format(LocalDateTime.parse(ts.substring(0, 14),
                    DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        } catch (Exception e) {
            return ts;
        }
    }

    /**
     * meta 文件（会话目录下名为 {@code meta} 的 JSON）解析结果
     *
     * <p>public（含 {@link #parseMetaJson(String)}）：除本地目录导入链路外，
     * {@link com.isim.dims.schedule.DataSliceFileRetentionSchedule#backfillSliceMetaFields()}
     * 也用同一份实现回填历史切片的 event_name / trigger_time，
     * 避免两处各自解析 meta 导致字段口径与触发时间换算漂移。</p>
     */
    @Data
    public static class MetaInfo {
        private String carType;
        private String collectType;
        private String eventName;
        /**
         * 软件版本号，meta 文件根级字段 system_name。
         * 前端切片详情页以本字段展示「软件版本」（替换原 utc_diff_us 字段）。
         */
        private String systemName;
        /**
         * 触发时刻（数据面本地时钟，微秒），meta.trigger_timestamp_us
         */
        private Long triggerTimestampUs;
        /**
         * 本地时钟 → UTC 偏移（微秒），meta.utc_diff_us
         */
        private Long utcDiffUs;
        /**
         * 触发时间（UTC，落库 data_slice.trigger_time）：meta 中
         * trigger_timestamp_us + utc_diff_us 即事件真实 UTC 触发时刻；
         * 前端展示时按东八区（北京时间 UTC+8）呈现。字段缺失/非法时为 null。
         */
        private LocalDateTime triggerTimeUtc;
    }

    /**
     * 解析 meta JSON 内容，提取 car_type / collect_type / event_name 字段，
     * 并按 trigger_timestamp_us + utc_diff_us 换算触发时间。
     *
     * <p>meta 字段类型不固定（字符串 / 布尔 / 数值 / 嵌套对象 / 数组），
     * 例如 "encrypt" 为嵌套对象。为避免 Jackson 把 Object 值强转为 String 失败，
     * 此处反序列化为 {@code Map<String, Object>}，仅读取所需的 String 字段。</p>
     *
     * <p>public static：本地目录导入（{@code handleSession}）与历史切片回填
     * （{@link com.isim.dims.schedule.DataSliceFileRetentionSchedule#backfillSliceMetaFields()}）
     * 共用同一份解析与换算实现。</p>
     *
     * @param jsonContent JSON 字符串
     * @return MetaInfo 对象，若解析失败或内容为空则返回 null
     */
    public static MetaInfo parseMetaJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(jsonContent, new TypeReference<Map<String, Object>>() {
            });
            MetaInfo info = new MetaInfo();
            info.setCarType(asString(map.get("car_type")));
            info.setCollectType(asString(map.get("collect_type")));
            info.setEventName(asString(map.get("event_name")));
            info.setSystemName(asString(map.get("system_name")));
            info.setTriggerTimestampUs(asLong(map.get("trigger_timestamp_us")));
            info.setUtcDiffUs(asLong(map.get("utc_diff_us")));
            info.setTriggerTimeUtc(toUtcTriggerTime(
                    info.getTriggerTimestampUs(), info.getUtcDiffUs()));
            return info;
        } catch (Exception e) {
            log.warn("Failed to parse meta JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 触发时间换算：meta 的 trigger_timestamp_us（数据面本地时钟，微秒）
     * 与 utc_diff_us（本地时钟 → UTC 偏移，微秒）相加，即事件真实 UTC 触发时刻。
     *
     * <p>结果按 UTC 返回（与 data_slice.start_ts/end_ts 语义一致，便于排序与比较），
     * 前端展示时再转换为东八区（北京时间）。</p>
     *
     * @return UTC 触发时刻；任一字段缺失时为 null（不落库，避免写入 1970 或错误时间）
     */
    private static LocalDateTime toUtcTriggerTime(Long triggerTimestampUs, Long utcDiffUs) {
        if (triggerTimestampUs == null || utcDiffUs == null) {
            return null;
        }
        long utcTimestampUs = triggerTimestampUs + utcDiffUs;
        Instant instant = Instant.ofEpochSecond(
                utcTimestampUs / 1_000_000,
                (utcTimestampUs % 1_000_000) * 1_000
        );
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 将 JSON 值安全转换为字符串：仅当值为 String 时原样返回，
     * 其它类型（Object/Array/Number/Boolean）返回 null，
     * 避免把嵌套对象错误地转为 "{...}" 字符串。
     */
    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * 将 JSON 值安全转换为 Long：Number 取 {@code longValue()}，纯数字字符串按
     * Long 解析；其它类型（对象/数组/布尔）或非法字符串返回 null，
     * 避免把非数值字段错误地当作时间戳参与触发时间换算。
     */
    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 选取足够可追溯的车辆标识：优先 VIN，其次车辆编号/车型代码，最后 MD5
     */
    private String resolveVin(VinMd5Mapping mapping, String md5) {
        if (mapping == null) {
            return md5;
        }
        return firstNonBlank(mapping.getVin(), mapping.getVehicleNo(), mapping.getModelCode(), md5);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private void addSkipped(DirectoryImportResponse response, String md5, String sessionId, String reason) {
        DirectoryImportResponse.SkippedItem item = new DirectoryImportResponse.SkippedItem();
        item.setMd5(md5);
        item.setSessionId(sessionId);
        item.setReason(reason);
        response.getSkipped().add(item);
    }

    /**
     * 列出目录下一级子目录；IO 失败时抛出 SCAN_FAILED（目录不可枚举属于硬错误）
     */
    private List<Path> listImmediateDirs(Path dir) {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("SCAN_FAILED: cannot list directory " + dir, e);
        }
        return dirs;
    }
}
