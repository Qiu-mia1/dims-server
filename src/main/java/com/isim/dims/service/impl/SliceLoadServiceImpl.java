package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.IngestionTaskStatusHistory;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.IngestionTaskStatusHistoryMapper;
import com.isim.dims.schedule.DownloadHirain835Schedule;
import com.isim.dims.service.BosDirectoryRestoreService;
import com.isim.dims.service.IngestionService;
import com.isim.dims.service.LocalDirectoryImportPipelineService;
import com.isim.dims.service.SliceLoadService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 数据切片「按需加载」Service 实现
 *
 * <p>MMT 调度只把 meta + 前视鱼眼落到本地（切片 status=META_READY，可播放鱼眼预览）；
 * 用户点「加载」时本实现：</p>
 * <ol>
 *   <li>按 {@code data_slice.import_bos_key} 统计 BOS 前缀数据量，给出预计耗时；</li>
 *   <li>把切片与任务置 LOADING，异步入 {@link ExecutorService} 全量拉取会话文件；</li>
 *   <li>下载完成后回到 {@code UPLOADING} 并复用本地目录导入流水线
 *       （{@link LocalDirectoryImportPipelineService#processOneImport}：
 *       转换 → 解析 → 打标 → READY），完成后即可正常回放。</li>
 * </ol>
 *
 * <p>失败（下载异常 / 流水线异常）把切片置 ERROR 并记录原因，再次点击「加载」可重试
 * （已下载文件按大小幂等跳过）。</p>
 *
 * @feature: slice
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SliceLoadServiceImpl implements SliceLoadService {

    /** 允许触发加载的切片状态：META_READY=未加载过；ERROR=加载失败后可重试 */
    private static final Set<String> LOADABLE_STATUSES = Set.of("META_READY", "ERROR");

    /** 已加载完成的状态（无需再加载） */
    private static final Set<String> LOADED_STATUSES = Set.of("READY", "WORN");

    /** 加载触发后的转换/解析阶段状态 */
    private static final Set<String> PIPELINE_STATUSES = Set.of(
            "UPLOADING", "CONVERTING", "PARSING", "TAGGING",
            "VIDEO_EXTRACTING", "STATS_EXTRACTING", "SCENE_EXTRACTING");

    private static final String STAGE_DOWNLOADING = "DOWNLOADING";
    private static final String STAGE_PROCESSING = "PROCESSING";
    private static final String STAGE_DONE = "DONE";
    private static final String STAGE_FAILED = "FAILED";
    private static final String STAGE_NOT_STARTED = "NOT_STARTED";

    /** 下载阶段占整体进度 0-20，流水线阶段占 20-100 */
    private static final int DOWNLOAD_PROGRESS_WEIGHT = 20;

    /** 下载过程中写任务进度的最小间隔（毫秒），避免逐文件刷库 */
    private static final long PROGRESS_DB_INTERVAL_MS = 3000L;

    /** 同一切片加载的分布式锁前缀 */
    private static final String LOAD_LOCK_PREFIX = "lock:slice-load:";

    private final DataSliceMapper dataSliceMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionTaskStatusHistoryMapper ingestionTaskStatusHistoryMapper;
    private final IngestionService ingestionService;
    private final LocalDirectoryImportPipelineService localDirectoryImportPipelineService;
    private final BosDirectoryRestoreService bosDirectoryRestoreService;
    private final DownloadHirain835Schedule downloadHirain835Schedule;
    private final RedissonClient redissonClient;

    /** 预计下载吞吐（字节/秒），缺省 20MB/s */
    @Value("${dims.load.download-bytes-per-second:20971520}")
    private long downloadBytesPerSecond;

    /** 转换 + 解析 + 打标流水线的经验耗时（秒），计入预计总耗时 */
    @Value("${dims.load.pipeline-seconds:600}")
    private long pipelineSeconds;

    /** 预计耗时下限（秒），避免小数据给出「0 秒」 */
    @Value("${dims.load.min-estimated-seconds:60}")
    private long minEstimatedSeconds;

    /** 并发加载数（每个加载会拉大文件 + 跑转换，限制避免打满磁盘/CPU） */
    @Value("${dims.load.concurrency:2}")
    private int concurrency;

    /** BOS 下载落地根目录（与调度一致；import_source_dir 缺失时按 localDir + bosKey 兜底定位会话目录） */
    @Value("${dims.download.local-dir:/data/downloads}")
    private String localDir;

    /** 内存进度表（sliceId → 下载阶段字节级进度；服务重启后回退为状态口径） */
    private final Map<String, LoadProgress> progressMap = new ConcurrentHashMap<>();

    private ExecutorService executor;

    private synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
                Thread t = new Thread(r, "slice-load");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public Map<String, Object> startLoad(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new IllegalStateException("SLICE_NOT_FOUND: sliceId=" + sliceId);
        }
        String bosKey = slice.getImportBosKey();
        if (bosKey == null || bosKey.isBlank()) {
            throw new IllegalStateException(
                    "LOAD_NOT_SUPPORTED: 切片无 BOS 会话前缀（import_bos_key），无法按需加载: sliceId=" + sliceId);
        }
        String status = slice.getStatus();
        if ("LOADING".equals(status)) {
            throw new IllegalStateException("LOAD_IN_PROGRESS: 切片正在加载中: sliceId=" + sliceId);
        }
        if (LOADED_STATUSES.contains(status)) {
            throw new IllegalStateException(
                    "LOAD_STATE_INVALID: 切片状态 " + status + " 已加载完成，无需重复加载: sliceId=" + sliceId);
        }
        if (!LOADABLE_STATUSES.contains(status)) {
            throw new IllegalStateException(
                    "LOAD_STATE_INVALID: 切片状态 " + status + " 不支持加载（仅 META_READY / ERROR 可加载）: sliceId=" + sliceId);
        }

        // 分布式锁：同一切片并发触发只放行一个（状态检查存在读后写竞态）
        RLock lock = redissonClient.getLock(LOAD_LOCK_PREFIX + sliceId);
        boolean locked = false;
        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LOAD_IN_PROGRESS: 获取加载锁被中断: sliceId=" + sliceId, e);
        }
        if (!locked) {
            throw new IllegalStateException("LOAD_IN_PROGRESS: 切片正在加载中: sliceId=" + sliceId);
        }

        try {
            // Hirain 源数据走专用下载/转换流水线（mcap_converter），不使用 MMT 通用拉取+导入流水线
            if ("Hirain".equals(slice.getSourceType())) {
                return startHirainLoad(sliceId, slice, bosKey);
            }

            // 1. 统计 BOS 前缀数据量（本地已有部分按大小幂等扣除），据此给出预计耗时
            BosDirectoryRestoreService.PrefixStat stat = bosDirectoryRestoreService.statPrefix(bosKey);
            if (stat.getFileCount() == 0) {
                throw new IllegalStateException("LOAD_SOURCE_EMPTY: BOS 前缀下无可用数据文件: " + bosKey);
            }
            long remainingBytes = stat.getRemainingBytes();
            long estimatedSeconds = estimateTotalSeconds(remainingBytes);

            // 2. 切片 + 任务进入 LOADING（任务复用已有记录，避免同切片多任务）
            IngestionTask task = prepareTask(sliceId, "LOADING", 0);
            slice.setStatus("LOADING");
            slice.setErrorMsg(null);
            dataSliceMapper.updateById(slice);

            LoadProgress progress = new LoadProgress(
                    stat.getTotalBytes(), stat.getExistingBytes(), estimatedSeconds);
            progressMap.put(sliceId, progress);

            String sessionDirPath = resolveSessionDirPath(slice, bosKey);

            // 3. 异步全量拉取 + 复用导入流水线
            executor().submit(() -> runLoad(sliceId, bosKey, task.getId(), sessionDirPath, progress));

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("slice_id", sliceId);
            resp.put("task_id", task.getId());
            resp.put("status", "LOADING");
            resp.put("estimated_seconds", estimatedSeconds);
            resp.put("total_bytes", stat.getTotalBytes());
            resp.put("downloaded_bytes", stat.getExistingBytes());
            resp.put("remaining_bytes", remainingBytes);
            log.info("按需加载已提交: sliceId={}, bosKey={}, 总字节={}, 本地已有={}, 预计耗时={}s",
                    sliceId, bosKey, stat.getTotalBytes(), stat.getExistingBytes(), estimatedSeconds);
            return resp;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 启动 Hirain 源数据的按需加载：创建/复用 IngestionTask，把切片置 LOADING，
     * 异步调用 {@link DownloadHirain835Schedule#loadSlice} 走完整下载/转换/上传流水线。
     */
    private Map<String, Object> startHirainLoad(String sliceId, DataSlice slice, String bosKey) {
        // 以切片已记录的 file_size 作为总字节数估算耗时（元数据阶段已统计 zip 包大小）
        long totalBytes = nullToZero(slice.getFileSize());
        long estimatedSeconds = estimateTotalSeconds(totalBytes);

        // 切片 + 任务进入 LOADING（任务复用已有记录，避免同切片多任务）
        IngestionTask task = prepareTask(sliceId, "LOADING", 0);
        slice.setStatus("LOADING");
        slice.setErrorMsg(null);
        dataSliceMapper.updateById(slice);

        LoadProgress progress = new LoadProgress(totalBytes, 0L, estimatedSeconds);
        progressMap.put(sliceId, progress);

        // 异步执行 Hirain 专用全量下载/转换/上传
        executor().submit(() -> runHirainLoad(sliceId, bosKey, task.getId(), progress));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("slice_id", sliceId);
        resp.put("task_id", task.getId());
        resp.put("status", "LOADING");
        resp.put("estimated_seconds", estimatedSeconds);
        resp.put("total_bytes", totalBytes);
        resp.put("downloaded_bytes", 0L);
        resp.put("remaining_bytes", totalBytes);
        log.info("Hirain 按需加载已提交: sliceId={}, bosKey={}, 总字节={}, 预计耗时={}s",
                sliceId, bosKey, totalBytes, estimatedSeconds);
        return resp;
    }

    /**
     * 异步任务体：调用 Hirain 专用下载/转换/上传流水线。
     * 由于流水线内部自行维护切片状态，本方法主要负责任务状态与内存进度的收尾。
     */
    private void runHirainLoad(String sliceId, String bosKey, String taskId, LoadProgress progress) {
        try {
            log.info("Hirain 按需加载开始: sliceId={}, bosKey={}", sliceId, bosKey);
            downloadHirain835Schedule.loadSlice(sliceId);
            progress.stage = STAGE_DONE;

            IngestionTask task = ingestionTaskMapper.selectById(taskId);
            if (task != null) {
                ingestionService.updateTaskStatus(task, "READY", 100);
            }
            log.info("Hirain 按需加载完成: sliceId={}", sliceId);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            log.error("Hirain 按需加载失败: sliceId={}, bosKey={}, err={}", sliceId, bosKey, message, e);
            progress.stage = STAGE_FAILED;
            progress.error = message;
            markFailed(sliceId, taskId, message);
        }
    }

    @Override
    public Map<String, Object> getLoadStatus(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new IllegalStateException("SLICE_NOT_FOUND: sliceId=" + sliceId);
        }
        IngestionTask task = selectTaskBySlice(sliceId);
        LoadProgress progress = progressMap.get(sliceId);
        String status = slice.getStatus();

        String stage;
        int overall;
        long remainingSeconds;
        long elapsedSeconds = progress == null ? 0L : secondsSince(progress.startedAt);
        long estimatedSeconds = progress == null ? 0L : progress.estimatedSeconds;
        long totalBytes = progress == null ? nullToZero(slice.getFileSize()) : progress.totalBytes;
        long downloadedBytes = progress == null ? 0L : progress.downloadedBytes;

        if (LOADED_STATUSES.contains(status)) {
            // 加载完成（READY/WORN）：清理内存进度，避免长期驻留
            stage = STAGE_DONE;
            overall = 100;
            remainingSeconds = 0L;
            downloadedBytes = Math.max(downloadedBytes, totalBytes);
            progressMap.remove(sliceId);
        } else if ("ERROR".equals(status)) {
            stage = STAGE_FAILED;
            overall = progress == null ? 0 : percentOfDownload(progress);
            remainingSeconds = 0L;
        } else if ("LOADING".equals(status)) {
            if (progress != null && !STAGE_PROCESSING.equals(progress.stage)) {
                // 下载阶段：整体进度 0-20，剩余时间按字节数与吞吐估算
                stage = STAGE_DOWNLOADING;
                overall = percentOfDownload(progress);
                remainingSeconds = ceilSeconds(Math.max(0L, totalBytes - downloadedBytes));
            } else {
                // 下载已完成、流水线已派发（切片状态尚未推进到 UPLOADING 的瞬间）
                stage = STAGE_PROCESSING;
                overall = pipelineOverall(task);
                remainingSeconds = pipelineRemainingSeconds(task);
            }
        } else if (PIPELINE_STATUSES.contains(status)) {
            stage = STAGE_PROCESSING;
            overall = pipelineOverall(task);
            remainingSeconds = pipelineRemainingSeconds(task);
        } else {
            // META_READY：尚未触发加载
            stage = STAGE_NOT_STARTED;
            overall = 0;
            remainingSeconds = 0L;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("slice_id", sliceId);
        resp.put("status", status);
        resp.put("stage", stage);
        resp.put("progress", overall);
        resp.put("downloaded_bytes", downloadedBytes);
        resp.put("total_bytes", totalBytes);
        resp.put("estimated_seconds", estimatedSeconds);
        resp.put("elapsed_seconds", elapsedSeconds);
        resp.put("remaining_seconds", remainingSeconds);
        resp.put("task_id", task == null ? null : task.getId());
        resp.put("error_msg", slice.getErrorMsg() != null
                ? slice.getErrorMsg()
                : (progress == null ? null : progress.error));
        return resp;
    }

    /**
     * 异步任务体：全量拉取 BOS 前缀 → 切回 UPLOADING → 复用本地目录导入流水线。
     * 任一步失败把切片置 ERROR（保留原因，用户可再次点「加载」重试）。
     */
    private void runLoad(String sliceId, String bosKey, String taskId,
                         String sessionDirPath, LoadProgress progress) {
        try {
            log.info("按需加载开始拉取: sliceId={}, bosKey={}", sliceId, bosKey);
            int files = bosDirectoryRestoreService.downloadPrefix(bosKey, (doneBytes, totalBytes) -> {
                progress.downloadedBytes = doneBytes;
                if (totalBytes > 0) {
                    progress.totalBytes = totalBytes;
                }
                updateTaskProgressThrottled(taskId, progress);
            });
            progress.stage = STAGE_PROCESSING;

            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice == null) {
                progress.stage = STAGE_FAILED;
                progress.error = "SLICE_NOT_FOUND: sliceId=" + sliceId;
                log.error("按需加载失败：下载完成后切片已不存在: sliceId={}", sliceId);
                return;
            }

            // 下载完成 → 回到 UPLOADING，交由导入流水线接管（转换 → 解析 → 打标 → READY）
            slice.setStatus("UPLOADING");
            slice.setErrorMsg(null);
            slice.setFileSize(progress.totalBytes);
            dataSliceMapper.updateById(slice);

            IngestionTask task = ingestionTaskMapper.selectById(taskId);
            if (task != null) {
                ingestionService.updateTaskStatus(task, "UPLOADING", DOWNLOAD_PROGRESS_WEIGHT);
            }

            log.info("按需加载拉取完成: sliceId={}, 本次新下载文件={}, 总字节={}, 进入转换流水线",
                    sliceId, files, progress.totalBytes);
            localDirectoryImportPipelineService.processOneImport(
                    new LocalDirectoryImportPipelineService.ImportedSession(
                            sliceId, taskId, sessionDirPath,
                            slice.getImportMd5(), slice.getSessionId(), slice.getVin(), null));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            log.error("按需加载失败: sliceId={}, bosKey={}, err={}", sliceId, bosKey, message, e);
            progress.stage = STAGE_FAILED;
            progress.error = message;
            markFailed(sliceId, taskId, message);
        }
    }

    /**
     * 失败落库：切片 ERROR + 任务 ERROR（保留 filePath 等既有元信息）
     */
    private void markFailed(String sliceId, String taskId, String message) {
        try {
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice != null) {
                slice.setStatus("ERROR");
                slice.setErrorMsg(message);
                dataSliceMapper.updateById(slice);
            }
            IngestionTask task = ingestionTaskMapper.selectById(taskId);
            if (task != null) {
                task.setErrorMsg(message);
                ingestionService.updateTaskStatus(task, "ERROR", 0);
            }
        } catch (Exception e) {
            log.error("按需加载失败态落库异常: sliceId={}, err={}", sliceId, e.getMessage(), e);
        }
    }

    /**
     * 准备导入任务：已有任务（首次导入建的 / 上次加载失败留下的）复用并改状态，
     * 避免同一切片出现多条 ingestion_task（流水线 waitForTask 以 selectOne 查询）。
     */
    private IngestionTask prepareTask(String sliceId, String status, int progress) {
        IngestionTask task = selectTaskBySlice(sliceId);
        if (task == null) {
            task = new IngestionTask();
            task.setId(UUID.randomUUID().toString());
            task.setSliceId(sliceId);
            task.setStatus(status);
            task.setProgress(progress);
            ingestionTaskMapper.insert(task);

            IngestionTaskStatusHistory history = new IngestionTaskStatusHistory();
            history.setTaskId(task.getId());
            history.setStatus(status);
            history.setProgress(progress);
            ingestionTaskStatusHistoryMapper.insert(history);
            return task;
        }
        task.setErrorMsg(null);
        ingestionService.updateTaskStatus(task, status, progress);
        return task;
    }

    /**
     * 解析会话目录本地绝对路径：优先用 meta-only 导入时记录的 import_source_dir；
     * 缺失（如被清理导致字段为空）时按「下载根目录 + BOS 对象前缀」兜底推导，与落地规则一致。
     */
    private String resolveSessionDirPath(DataSlice slice, String bosKey) {
        String sourceDir = slice.getImportSourceDir();
        if (sourceDir != null && !sourceDir.isBlank()) {
            return sourceDir;
        }
        String prefix = bosKey.endsWith("/") ? bosKey.substring(0, bosKey.length() - 1) : bosKey;
        return Path.of(localDir).resolve(prefix).toAbsolutePath().toString();
    }

    /**
     * 加载任务查询：同一切片可能存在历史任务（首次导入 + 本次加载复用），取最新一条
     */
    private IngestionTask selectTaskBySlice(String sliceId) {
        return ingestionTaskMapper.selectOne(
                new LambdaQueryWrapper<IngestionTask>()
                        .eq(IngestionTask::getSliceId, sliceId)
                        .orderByDesc(IngestionTask::getCreatedAt)
                        .last("LIMIT 1"));
    }

    /**
     * 下载过程中写任务进度（限流，避免逐文件刷库）：下载阶段映射为整体 0-20
     */
    private void updateTaskProgressThrottled(String taskId, LoadProgress progress) {
        long now = System.currentTimeMillis();
        if (now - progress.lastDbUpdateMs < PROGRESS_DB_INTERVAL_MS) {
            return;
        }
        progress.lastDbUpdateMs = now;
        try {
            IngestionTask task = ingestionTaskMapper.selectById(taskId);
            if (task != null) {
                ingestionService.updateTaskStatus(task, "LOADING", percentOfDownload(progress));
            }
        } catch (Exception e) {
            log.warn("更新加载进度失败（忽略）: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    /**
     * 预计总耗时（秒）= 剩余字节 / 吞吐 + 流水线经验耗时，下限 {@code minEstimatedSeconds}
     */
    private long estimateTotalSeconds(long remainingBytes) {
        return Math.max(Math.max(0L, minEstimatedSeconds),
                ceilSeconds(remainingBytes) + Math.max(0L, pipelineSeconds));
    }

    /**
     * 字节数 → 下载耗时（秒，向上取整）
     */
    private long ceilSeconds(long bytes) {
        long bps = Math.max(1L, downloadBytesPerSecond);
        return bytes <= 0 ? 0L : (bytes + bps - 1) / bps;
    }

    /**
     * 下载阶段进度 → 整体进度（0-20）
     */
    private int percentOfDownload(LoadProgress progress) {
        if (progress.totalBytes <= 0) {
            return 0;
        }
        long pct = progress.downloadedBytes * DOWNLOAD_PROGRESS_WEIGHT / progress.totalBytes;
        return (int) Math.min(DOWNLOAD_PROGRESS_WEIGHT, Math.max(0L, pct));
    }

    /**
     * 流水线阶段进度 → 整体进度（20-100）
     */
    private int pipelineOverall(IngestionTask task) {
        int taskProgress = task == null || task.getProgress() == null ? 0 : task.getProgress();
        taskProgress = Math.max(0, Math.min(100, taskProgress));
        return DOWNLOAD_PROGRESS_WEIGHT
                + taskProgress * (100 - DOWNLOAD_PROGRESS_WEIGHT) / 100;
    }

    /**
     * 流水线阶段剩余时间（按经验耗时与任务进度线性折算）
     */
    private long pipelineRemainingSeconds(IngestionTask task) {
        int taskProgress = task == null || task.getProgress() == null ? 0 : task.getProgress();
        taskProgress = Math.max(0, Math.min(100, taskProgress));
        return Math.max(0L, pipelineSeconds - pipelineSeconds * taskProgress / 100);
    }

    private static long secondsSince(Instant start) {
        return start == null ? 0L : Math.max(0L, Instant.now().getEpochSecond() - start.getEpochSecond());
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 内存加载进度（下载阶段字节级进度 + 预计耗时 + 失败原因）
     */
    private static class LoadProgress {

        private volatile long totalBytes;
        private volatile long downloadedBytes;
        private volatile long lastDbUpdateMs;
        private volatile String stage = STAGE_DOWNLOADING;
        private volatile String error;
        private final long estimatedSeconds;
        private final Instant startedAt = Instant.now();

        LoadProgress(long totalBytes, long downloadedBytes, long estimatedSeconds) {
            this.totalBytes = totalBytes;
            this.downloadedBytes = downloadedBytes;
            this.estimatedSeconds = estimatedSeconds;
            this.lastDbUpdateMs = System.currentTimeMillis();
        }
    }
}
