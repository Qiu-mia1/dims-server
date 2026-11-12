package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.config.CameraEventConfig;
import com.isim.dims.config.PgvectorConfig;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.GpsTrajectory;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.GpsTrajectoryMapper;
import com.isim.dims.service.GpsTrajectoryService;
import com.isim.dims.service.VisualizationPipelineService;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 可视化数据异步提取流水线 Service 实现
 * <p>
 * 独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效。
 * 所有提取结果通过 ConcurrentHashMap 回传状态。
 * <p>
 * 任务状态流转：PROCESSING → READY，或 PROCESSING → ERROR。
 *
 * @feature: visualization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisualizationPipelineServiceImpl implements VisualizationPipelineService {

    /**
     * 任务 ID 必须是标准 UUID（recoverTaskFromDisk 路径遍历防护）
     */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final GpsTrajectoryMapper gpsTrajectoryMapper;
    private final GpsTrajectoryService gpsTrajectoryService;
    private final DataSliceMapper dataSliceMapper;
    private final PythonExecutor pythonExecutor;
    private final StorageAccess storageAccess;
    /**
     * 视频段提取并行转码线程池（AsyncConfig#videoExtractExecutor，固定 3 路并发）
     */
    private final ThreadPoolTaskExecutor videoExtractExecutor;
    /**
     * pgvector 向量库连接配置（注入 extract_video_segment.py 用于写入帧图 embedding）
     */
    private final PgvectorConfig pgvectorConfig;
    /**
     * 摄像头/事件配置（camera_event_config.json，来源 CX835-MMT-筛选器-摄像头数据.xlsx）：
     * 提供 topic → 目标分辨率（前视 1080P，其余 720P）
     */
    private final CameraEventConfig cameraEventConfig;

    @Value("${dims.siglip.embed-dims:768}")
    private int siglipEmbedDims;

    @Value("${dims.siglip.url:http://192.168.114.2:30800}")
    private String siglipUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dims.python.timeout-sec:300}")
    private int pythonTimeoutSec;

    @Value("${dims.task.disk-retention-hours:48}")
    private int diskRetentionHours;

    /**
     * 内存任务状态存储（taskId → 任务状态）
     */
    private final ConcurrentHashMap<String, TaskState> taskStore = new ConcurrentHashMap<>();

    /**
     * 任务状态定时清理线程池（每 30 秒清理一次）
     */
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-cleanup");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        this.cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpiredTasks, 30, 30, TimeUnit.SECONDS);
        log.info("Task cleanup scheduler started (retention: {}s, interval: {}s)", TASK_RETENTION_SECONDS, 30);
    }

    @PreDestroy
    public void destroy() {
        this.cleanupExecutor.shutdown();
        try {
            if (!this.cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Task cleanup scheduler shut down");
    }

    /**
     * 任务完成后的内存保留时间（秒）
     */
    private static final long TASK_RETENTION_SECONDS = 60;

    // ========================================================================
    // 任务状态管理（实例方法）
    // ========================================================================

    @Override
    public String createTask(String sliceId, String type) {
        return createTask(sliceId, type, null);
    }

    @Override
    public String createTask(String sliceId, String type, List<String> topics) {
        // 幂等检查（P0）：同 slice + 同提取类型已有 PROCESSING 任务且已覆盖本次请求的 topics →
        // 直接复用已有 taskId，防止前端重复 POST 触发多个 @Async 任务并发写同一输出文件（文件损坏）。
        // synchronized 保证并发 POST 场景下 check-then-act 的原子性（见 extractVideoSegment 的双保险）。
        synchronized (this) {
            for (TaskState state : taskStore.values()) {
                if (sliceId.equals(state.sliceId)
                        && "PROCESSING".equals(state.status)
                        && type.equals(state.extractType)
                        && coversTopics(state.topics, topics)) {
                    log.info("Reusing in-flight extraction task: taskId={}, sliceId={}, type={}, topics={}",
                            state.taskId, sliceId, type, topics);
                    return state.taskId;
                }
            }

            String taskId = UUID.randomUUID().toString();
            TaskState state = new TaskState();
            state.taskId = taskId;
            state.sliceId = sliceId;
            state.status = "PROCESSING";
            state.progress = 0;
            state.extractType = type;
            state.topics = topics != null ? new ArrayList<>(topics) : null;
            taskStore.put(taskId, state);
            return taskId;
        }
    }

    /**
     * 判断已有任务的 topics 是否覆盖本次请求的 topics
     *
     * <p>请求 topics 为空（如轨迹提取，无 topic 概念）→ 视为覆盖（按 spec：
     * 同 slice + 同 type 即复用）。已有任务未记录 topics（旧任务）→ 保守
     * 不视为覆盖，避免新请求 topic 被静默跳过。</p>
     */
    private boolean coversTopics(List<String> existingTopics, List<String> requestedTopics) {
        if (requestedTopics == null || requestedTopics.isEmpty()) {
            return true;
        }
        if (existingTopics == null || existingTopics.isEmpty()) {
            return false;
        }
        return existingTopics.containsAll(requestedTopics);
    }

    @Override
    public Map<String, Object> getTaskSnapshot(String taskId) {
        TaskState state = taskStore.get(taskId);
        if (state == null) return null;
        return buildTaskSnapshot(state);
    }

    @Override
    public Map<String, Object> getVideoExtractionTask(String taskId) {
        // Same two-step pattern as getTaskSnapshot + recoverTaskFromDisk used
        // by TrajectoryServiceImpl.getTrajectoryTask() and VisualizationServiceImpl.getSignalExtraction()
        Map<String, Object> snapshot = getTaskSnapshot(taskId);
        if (snapshot == null) {
            // Memory miss → try disk recovery (task cleaned up or service restart)
            snapshot = recoverTaskFromDisk(taskId);
        }
        return snapshot;
    }

    @Override
    public String findActiveTaskBySlice(String sliceId, String extractType) {
        for (TaskState state : taskStore.values()) {
            if (sliceId.equals(state.sliceId)
                    && "PROCESSING".equals(state.status)
                    && (extractType == null || extractType.equals(state.extractType))) {
                return state.taskId;
            }
        }
        return null;
    }

    // ========================================================================
    // GPS 轨迹提取
    // ========================================================================

    @Override
    @Async
    public void processExtractTrajectory(String taskId, String sliceId, String bagFilePath,
                                         ExtractRequest request) {
        log.info("Trajectory extraction pipeline started: taskId={}, sliceId={}", taskId, sliceId);

        // 幂等防护：同一 taskId 只允许执行一次（并发 POST 复用同一任务时，重复的 @Async 调用直接返回）。
        // taskId 不在 taskStore 中（测试直调 / 理论上的任务清理竞态）→ 放行，保持原有 no-op 语义
        TaskState state = taskStore.get(taskId);
        if (state != null && !state.started.compareAndSet(false, true)) {
            log.warn("Trajectory extraction skipped (task already started): taskId={}", taskId);
            return;
        }

        try {
            updateProgress(taskId, 10);

            // Python 只能消费本地路径：物化 bag（local 零拷贝 / minio 下载缓存）
            String localBag = storageAccess.materializeLocal(bagFilePath).toString();

            // 输出引用（key 规范 trajectories/{sliceId}/trajectory.json；minio 模式先写 staging）
            String outputKey = StorageKeys.trajectory(sliceId);
            Path outputPath = storageAccess.isMinioMode()
                    ? storageAccess.stageDir("trajectories").resolve(sliceId).resolve("trajectory.json")
                    : storageAccess.resolveLocalPath(outputKey);

            // 构建 Python 参数
            Map<String, Object> args = new HashMap<>();
            args.put("bag_path", localBag);
            args.put("output_path", outputPath.toString());
            args.put("topics", request.getTopics() != null ? request.getTopics() : new ArrayList<>());
            args.put("max_points", 5000);

            // 调用 Python extract_gps_trajectory.py
            String result = pythonExecutor.execute("extract_gps_trajectory.py", args, pythonTimeoutSec);

            // 解析 Python 输出
            Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
            boolean success = Boolean.TRUE.equals(resultMap.get("success"));
            if (!success) {
                throw new RuntimeException("Trajectory extraction failed: " + result);
            }

            // minio 模式：staging 产物上传对象存储（local 模式 Python 已直接写最终位置）
            if (storageAccess.isMinioMode()) {
                storageAccess.put(outputKey, outputPath, "application/json");
            }

            updateProgress(taskId, 60);

            // 解析轨迹点并批量写入 DB
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trajectoryData =
                    (List<Map<String, Object>>) resultMap.get("trajectory");

            if (trajectoryData != null && !trajectoryData.isEmpty()) {
                // 先清空该切片的旧轨迹数据
                gpsTrajectoryMapper.delete(
                        new LambdaQueryWrapper<GpsTrajectory>()
                                .eq(GpsTrajectory::getSliceId, sliceId)
                );

                // 批量插入新轨迹点（使用 saveBatch 避免 N 次单条 INSERT）
                List<GpsTrajectory> points = new ArrayList<>();
                for (Map<String, Object> pt : trajectoryData) {
                    GpsTrajectory gpsPt = new GpsTrajectory();
                    gpsPt.setSliceId(sliceId);
                    gpsPt.setTs(parseTime((String) pt.get("ts")));
                    gpsPt.setLat(toDouble(pt.get("lat")));
                    gpsPt.setLon(toDouble(pt.get("lon")));
                    gpsPt.setAltitude(toDouble(pt.get("altitude")));
                    gpsPt.setHeading(toDouble(pt.get("heading")));
                    gpsPt.setSpeed(toDouble(pt.get("speed")));
                    points.add(gpsPt);
                }
                gpsTrajectoryService.saveBatch(points);
                updateTrajectoryMarker(sliceId, "EXTRACTED");
            } else {
                // 提取成功但 0 点（如 topic 被归为 gnss_imu 但无实际 GNSS 数据）：
                // 持久化"已确认空"标记，防止 GET 每次重复触发提取造成死循环
                log.warn("Trajectory extraction returned 0 points, marking slice as EMPTY: sliceId={}", sliceId);
                updateTrajectoryMarker(sliceId, "EMPTY");
            }

            updateProgress(taskId, 100);
            setTaskStatus(taskId, "READY");

            log.info("Trajectory extraction completed: taskId={}, points={}",
                    taskId, trajectoryData != null ? trajectoryData.size() : 0);

        } catch (Exception e) {
            log.error("Trajectory extraction FAILED: taskId={}", taskId, e);
            failTask(taskId, e.getMessage());
        }
    }

    // ========================================================================
    // 视频段提取
    // ========================================================================

    @Override
    @Async
    public void processExtractVideo(String taskId, String sliceId, String bagFilePath,
                                    ExtractRequest request) {
        log.info("Video extraction pipeline started: taskId={}, sliceId={}", taskId, sliceId);

        // 幂等防护：同一 taskId 只允许执行一次（并发 POST 复用同一任务时，重复的 @Async 调用直接返回）。
        // taskId 不在 taskStore 中（测试直调 / 理论上的任务清理竞态）→ 放行，保持原有 no-op 语义
        TaskState state = taskStore.get(taskId);
        if (state != null && !state.started.compareAndSet(false, true)) {
            log.warn("Video extraction skipped (task already started): taskId={}", taskId);
            return;
        }

        try {
            updateProgress(taskId, 10);

            // Python 只能消费本地路径：物化 bag（local 零拷贝 / minio 下载缓存）
            String localBag = storageAccess.materializeLocal(bagFilePath).toString();
            // minio 模式：清理该切片残留 staging 视频文件（上次失败任务遗留），避免误提交
            if (storageAccess.isMinioMode()) {
                storageAccess.deleteRecursively(storageAccess.stageDir("videos").resolve(sliceId));
            }

            // CFDI meta 时钟偏移（微秒）：bag 索引时间是数据面本地时钟，
            // 真实 UTC = bag 时间戳 + utc_diff_us × 1000。slice 的 start_ts/end_ts
            // 已含该偏移（parse_rosbag 阶段换算），提取时必须把同一偏移传给 Python，
            // 否则请求窗口与 bag 帧时间整体错开 → 抽不到任何帧。
            Long utcDiffUs = resolveUtcDiffUs(sliceId);

            List<String> topics = request.getTopics();
            final Long utcDiffUsLong = utcDiffUs;
            if (topics != null && !topics.isEmpty()) {
                // 并行提取（P1）：每个 topic 提交为独立子任务（ffmpeg HEVC→H.264 为 CPU 密集任务，
                // 并发度由 videoExtractExecutor 固定 3 路控制，避免打满 CPU；不同 topic 输出不同
                // 文件，并行写互不冲突）。单 taskId 的并发提交由上方 started 标志拦截。
                Map<String, Future<?>> topicFutures = new LinkedHashMap<>();
                log.info("Video extraction pipeline started start: taskId={}, sliceId={}, topics={}",
                        taskId, sliceId, topics);
                for (String topic : topics) {
                    topicFutures.put(topic, videoExtractExecutor.submit(
                            () -> extractSingleVideoTopic(taskId, sliceId, localBag, request, topic, utcDiffUsLong)));
                }
                log.info("Video extraction pipeline started end: taskId={}, sliceId={}, topics={}",
                        taskId, sliceId, topics);

                // 汇总所有子任务：某 topic 失败 → 记录错误并继续处理其余 topic
                Map<String, String> failures = new LinkedHashMap<>();
                boolean interrupted = false;
                int completed = 0;
                for (Map.Entry<String, Future<?>> entry : topicFutures.entrySet()) {
                    try {
                        entry.getValue().get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        failures.put(entry.getKey(), cause.getMessage());
                        log.error("Video segment extraction failed: taskId={}, topic={}", taskId, entry.getKey(), cause);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        interrupted = true;
                        failures.put(entry.getKey(), "interrupted");
                        log.error("Video extraction interrupted: taskId={}, topic={}", taskId, entry.getKey(), e);
                        break;
                    }
                    completed++;
                    // 进度 = 已完成的 topic 数 / 总 topic 数
                    updateProgress(taskId, 10 + (completed * 80 / topics.size()));
                }

                if (interrupted) {
                    failTask(taskId, "Video extraction interrupted, completed " + completed + "/" + topics.size() + " topics");
                    return;
                }
                if (failures.size() == topics.size()) {
                    failTask(taskId, "All video topics failed: " + formatFailures(failures));
                    return;
                }
                if (!failures.isEmpty()) {
                    // 部分失败：已成功的 topic 文件仍可用，任务整体标记完成并记录失败明细
                    log.warn("Video extraction completed with {} failed topic(s): taskId={}, failures={}",
                            failures.size(), taskId, formatFailures(failures));
                }
            }

            updateProgress(taskId, 100);
            setTaskStatus(taskId, "READY");

            log.info("Video extraction completed: taskId={}", taskId);

        } catch (Exception e) {
            log.error("Video extraction FAILED: taskId={}", taskId, e);
            failTask(taskId, e.getMessage());
        }
    }

    /**
     * 提取单个 topic 的视频段（并行子任务，线程安全）
     *
     * <p>PythonExecutor 每次调用启动独立进程与临时文件，无共享可变状态，可安全并发。</p>
     *
     * @throws RuntimeException 该 topic 提取失败时抛出（由调用方 Future 汇总）
     */
    private void extractSingleVideoTopic(String taskId, String sliceId, String localBag,
                                         ExtractRequest request, String topic, Long utcDiffUs) {
        String sanitizedTopic = topic.replace("/", "_").replace(".", "_");
        // 输出引用（key 规范 videos/{sliceId}/{sanitized}.mp4；minio 模式先写 staging）
        String outputKey = StorageKeys.video(sliceId, sanitizedTopic + ".mp4");
        Path outputPath = storageAccess.isMinioMode()
                ? storageAccess.stageDir("videos").resolve(sliceId).resolve(sanitizedTopic + ".mp4")
                : storageAccess.resolveLocalPath(outputKey);

        Map<String, Object> args = new HashMap<>();
        args.put("bag_path", localBag);
        args.put("output_path", outputPath.toString());
        args.put("topic", topic);

        // 摄像头目标分辨率（camera_event_config.json）：前视摄像头 1080P，其余 720P；
        // 未在配置中的 topic 不传，Python 侧回退默认值
        CameraEventConfig.CameraChannel channel = cameraEventConfig.getCameraByTopic(topic);
        if (channel != null && channel.getWidth() > 0 && channel.getHeight() > 0) {
            args.put("target_width", channel.getWidth());
            args.put("target_height", channel.getHeight());
            log.debug("Video extraction resolution for topic={}: {}x{} ({})",
                    topic, channel.getWidth(), channel.getHeight(), channel.getResolution());
        }

        // 每 5 秒存一张图片：Python 脚本从产出的 MP4 中每隔 5s 抽一帧 JPEG，
        // 命名 frame_<纳秒时间戳>.jpg。local 模式直接写最终位置；minio 模式写 staging。
        String topicSafeImageDir = StorageKeys.videoImagesTopicDir(sliceId, sanitizedTopic);
        Path imageDir = storageAccess.isMinioMode()
                ? storageAccess.stageDir("images").resolve(sliceId).resolve(sanitizedTopic)
                : storageAccess.resolveLocalPath(topicSafeImageDir);
        args.put("images_dir", imageDir.toString());
        args.put("frame_interval_sec", 2);
        // 供 extract_video_segment.py 保存帧图后将 768 维 embedding 写入 pgvector
        args.put("slice_id", sliceId);
        // pgvector 独立向量库连接参数（与检索端 ImageAssetMapper @DS("pgvector") 同库）
        args.put("pg_host", pgvectorConfig.getHost());
        args.put("pg_port", pgvectorConfig.getPort());
        args.put("pg_db", pgvectorConfig.getDatabase());
        args.put("pg_user", pgvectorConfig.getUsername());
        args.put("pg_password", pgvectorConfig.getPassword());
        args.put("embed_dims", siglipEmbedDims);
        args.put("siglip_url", siglipUrl);

        // RFC3339 字符串转纳秒时间戳
        if (request.getStartTs() != null && !request.getStartTs().isEmpty()) {
            Instant startInstant = Instant.parse(request.getStartTs());
            args.put("start_ns", startInstant.getEpochSecond() * 1_000_000_000L + startInstant.getNano());
        }
        if (request.getEndTs() != null && !request.getEndTs().isEmpty()) {
            Instant endInstant = Instant.parse(request.getEndTs());
            args.put("end_ns", endInstant.getEpochSecond() * 1_000_000_000L + endInstant.getNano());
        }
        args.put("output_format", "mp4");

        // CFDI meta 时钟偏移（微秒）：bag 索引时间是数据面本地时钟，
        // 真实 UTC = bag 时间戳 + utc_diff_us × 1000。start_ns/end_ns 来自
        // slice.start_ts/end_ts（已含该偏移），Python 需按同一偏移换算 bag 时间戳
        // 后再过滤，否则窗口与帧时间整体错开 → 0 帧。为 NULL（非 CFDI 来源）时
        // 不传，由 Python 侧自动校准兜底。
        if (utcDiffUs != null) {
            args.put("utc_diff_us", utcDiffUs);
        }

        try {
            log.debug("Extracting video segment: taskId={}, topic={}, args={}", taskId, topic, args);
            String result = pythonExecutor.execute("extract_video_segment.py", args, pythonTimeoutSec);
            log.info("Video segment extracted: taskId={}, topic={}, result={}", taskId, topic, result);

            // minio 模式：staging 产物上传对象存储
            if (storageAccess.isMinioMode()) {
                storageAccess.put(outputKey, outputPath, "video/mp4");
            }

            // 保存每 5 秒截取的帧图元数据（index.json），并上传图片（minio）
            persistTopicImages(sliceId, sanitizedTopic, topic, imageDir, result);
        } catch (Exception e) {
            // 失败时清理残留 staging 文件，避免后续任务误提交
            if (storageAccess.isMinioMode()) {
                try {
                    Files.deleteIfExists(outputPath);
                } catch (Exception cleanup) {
                    log.debug("Failed to clean staging video file: {}", outputPath, cleanup);
                }
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * 保存视频提取时每 5 秒截取的帧图片元数据（index.json），minio 模式同时上传图片。
     *
     * <p>index.json 结构：{ slice_id, topic, topic_safe, images: [{timestamp, file_name}] }，
     * 供前端浏览帧图与语义检索（以文搜图 / 以图搜图 / 特征搜图）使用。</p>
     */
    private void persistTopicImages(String sliceId, String topicSafe, String topic,
                                    Path imageDir, String result) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
            Object imagesRaw = resultMap.get("images");
            if (!(imagesRaw instanceof List<?> imageList)) {
                log.debug("Video extraction produced no frame images (images_dir unset), topic={}", topic);
                return;
            }
            List<Map<String, Object>> images = new ArrayList<>();
            for (Object o : imageList) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> img = new LinkedHashMap<>();
                    img.put("timestamp", m.get("timestamp"));
                    img.put("file_name", m.get("file_name"));
                    images.add(img);
                }
            }
            if (images.isEmpty()) {
                return;
            }

            // minio 模式：将每张帧图上传到 images/{sliceId}/{topicSafe}/
            if (storageAccess.isMinioMode() && Files.isDirectory(imageDir)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(imageDir)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                            .forEach(p -> storageAccess.put(
                                    StorageKeys.videoImagesTopicDir(sliceId, topicSafe) + p.getFileName(),
                                    p, "image/jpeg"));
                }
            }

            // 写 index.json 元数据
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("slice_id", sliceId);
            index.put("topic", topic);
            index.put("topic_safe", topicSafe);
            index.put("images", images);
            String indexKey = StorageKeys.videoImagesIndex(sliceId, topicSafe);
            storageAccess.put(indexKey, objectMapper.writeValueAsBytes(index), "application/json");
            log.info("Persisted {} frame images for topic={}, sliceId={}", images.size(), topic, sliceId);
        } catch (Exception e) {
            log.warn("Failed to persist frame images metadata: sliceId={}, topic={}", sliceId, topic, e);
        }
    }

    /**
     * 格式化失败明细（topic: 错误信息，按提交顺序，截断至 2000 字符）
     */
    private String formatFailures(Map<String, String> failures) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : failures.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        String msg = sb.toString();
        return msg.length() > 2000 ? msg.substring(0, 2000) + "..." : msg;
    }

    // ========================================================================
    // 信号数据提取
    // ========================================================================

    @Override
    @Async
    public void processExtractSignals(String taskId, String sliceId, String bagFilePath,
                                      ExtractRequest request) {
        log.info("Signal extraction pipeline started: taskId={}, sliceId={}", taskId, sliceId);

        // 幂等防护：同一 taskId 只允许执行一次（并发 POST 复用同一任务时，重复的 @Async 调用直接返回）。
        // taskId 不在 taskStore 中（测试直调 / 理论上的任务清理竞态）→ 放行，保持原有 no-op 语义
        TaskState state = taskStore.get(taskId);
        if (state != null && !state.started.compareAndSet(false, true)) {
            log.warn("Signal extraction skipped (task already started): taskId={}", taskId);
            return;
        }

        try {
            updateProgress(taskId, 10);

            // Python 只能消费本地路径：物化 bag（local 零拷贝 / minio 下载缓存）
            String localBag = storageAccess.materializeLocal(bagFilePath).toString();

            // 输出引用（key 规范 signals/{sliceId}/signals.json；minio 模式先写 staging）
            String outputKey = StorageKeys.signals(sliceId);
            Path outputFile = storageAccess.isMinioMode()
                    ? storageAccess.stageDir("signals").resolve(sliceId).resolve("signals.json")
                    : storageAccess.resolveLocalPath(outputKey);
            Files.createDirectories(outputFile.getParent());

            // 构建 Python 参数
            Map<String, Object> args = new HashMap<>();
            args.put("bag_path", localBag);
            args.put("output_path", outputFile.toString());
            args.put("topics", request.getTopics() != null ? request.getTopics() : new ArrayList<>());

            // RFC3339 字符串转纳秒时间戳
            if (request.getStartTs() != null && !request.getStartTs().isEmpty()) {
                Instant startInstant = Instant.parse(request.getStartTs());
                args.put("start_ns", startInstant.getEpochSecond() * 1_000_000_000L + startInstant.getNano());
            }
            if (request.getEndTs() != null && !request.getEndTs().isEmpty()) {
                Instant endInstant = Instant.parse(request.getEndTs());
                args.put("end_ns", endInstant.getEpochSecond() * 1_000_000_000L + endInstant.getNano());
            }

            args.put("max_points_per_signal", request.getMaxPointsPerSignal() != null
                    ? request.getMaxPointsPerSignal() : 10000);

            // CFDI meta 时钟偏移（微秒）：bag 索引时间是数据面本地时钟，
            // 真实 UTC = bag 时间戳 + utc_diff_us × 1000。start_ns/end_ns 是 UTC 值，
            // Python 需按同一偏移换算 bag 时间戳后再过滤，否则窗口整体错开 → 0 条。
            putUtcDiffUs(args, sliceId);

            // 调用 Python extract_signals.py
            String result = pythonExecutor.execute("extract_signals.py", args, pythonTimeoutSec);

            // 解析 Python 输出并验证
            Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
            boolean success = Boolean.TRUE.equals(resultMap.get("success"));

            if (!success) {
                throw new RuntimeException("Signal extraction failed: " + result);
            }

            // 将结果写入缓存文件（minio 模式 staging 文件，成功后上传对象存储）
            objectMapper.writeValue(outputFile.toFile(), resultMap);
            if (storageAccess.isMinioMode()) {
                storageAccess.put(outputKey, outputFile, "application/json");
            }

            updateProgress(taskId, 100);
            String expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(24)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            // resultPath 语义：local 模式为绝对路径，minio 模式为相对 key（契约：snake_case output_path）
            String resultPath = storageAccess.isMinioMode() ? outputKey : outputFile.toString();
            completeTask(taskId, resultPath, expiresAt);

            log.info("Signal extraction completed: taskId={}, outputPath={}", taskId, resultPath);

        } catch (Exception e) {
            log.error("Signal extraction FAILED: taskId={}", taskId, e);
            failTask(taskId, e.getMessage());
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 更新 data_slice 的轨迹提取结果标记
     *
     * @param sliceId 切片 UUID
     * @param status  EXTRACTED（提取成功有点）或 EMPTY（提取成功但 0 点）
     */
    private void updateTrajectoryMarker(String sliceId, String status) {
        try {
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice == null) {
                log.warn("updateTrajectoryMarker skipped: DataSlice not found for sliceId={}", sliceId);
                return;
            }
            slice.setTrajectoryStatus(status);
            slice.setTrajectoryCheckedAt(LocalDateTime.now(ZoneOffset.UTC));
            dataSliceMapper.updateById(slice);
            log.info("Trajectory marker updated: sliceId={}, trajectory_status={}", sliceId, status);
        } catch (Exception e) {
            log.warn("updateTrajectoryMarker failed: sliceId={}, status={}", sliceId, status, e);
        }
    }

    private void updateProgress(String taskId, int progress) {
        TaskState state = taskStore.get(taskId);
        if (state != null) {
            state.progress = progress;
        }
    }

    /**
     * 读取切片的 CFDI meta 时钟偏移（微秒）。用于把 bag 的数据面本地时钟换算到 UTC，
     * 与 slice.start_ts/end_ts（parse_rosbag 已加偏移）保持同一时间轴。
     *
     * @return utc_diff_us；非 CFDI 来源或读取失败时为 null（由 Python 侧自动校准兜底）
     */
    private Long resolveUtcDiffUs(String sliceId) {
        if (sliceId == null) {
            return null;
        }
        try {
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            return slice == null ? null : slice.getUtcDiffUs();
        } catch (Exception e) {
            log.warn("Failed to load utc_diff_us for sliceId={} — falling back to auto calibration",
                    sliceId, e);
            return null;
        }
    }

    /** 将 utc_diff_us 写入 Python 入参（仅非 null 时写入）。 */
    private void putUtcDiffUs(Map<String, Object> args, String sliceId) {
        Long utcDiffUs = resolveUtcDiffUs(sliceId);
        if (utcDiffUs != null) {
            args.put("utc_diff_us", utcDiffUs);
        }
    }

    private void setTaskStatus(String taskId, String status) {
        TaskState state = taskStore.get(taskId);
        if (state != null) {
            state.status = status;
            if ("READY".equals(status) || "ERROR".equals(status)) {
                state.completedAt = OffsetDateTime.now(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                persistTaskMetadata(state);
            }
        }
    }

    /**
     * 完成任务并设置结果路径和过期时间
     * <p>
     * 统一处理 READY 终态的 resultPath 和 expiresAt 字段。
     * 与 setTaskStatus 的区别在于额外携带结果数据相关的元信息。
     * 用于 processExtractSignals 等需要记录输出路径的场景。
     *
     * @param taskId     任务 UUID
     * @param resultPath 结果文件路径
     * @param expiresAt  结果过期时间（RFC3339 格式）
     */
    private void completeTask(String taskId, String resultPath, String expiresAt) {
        TaskState state = taskStore.get(taskId);
        if (state != null) {
            state.status = "READY";
            state.progress = 100;
            state.resultPath = resultPath;
            state.expiresAt = expiresAt;
            state.completedAt = OffsetDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            persistTaskMetadata(state);
        }
    }

    private void failTask(String taskId, String errorMsg) {
        TaskState state = taskStore.get(taskId);
        if (state != null) {
            state.status = "ERROR";
            state.errorMsg = errorMsg;
            state.completedAt = OffsetDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            persistTaskMetadata(state);
        }
    }

    private LocalDateTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        try {
            return LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception e2) {
                log.warn("Failed to parse time: {}", timeStr, e2);
                return null;
            }
        }
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // 构建 & 持久化 & 恢复 & 清理
    // ========================================================================

    /**
     * 构造任务状态快照 Map（与原有 getTaskSnapshot 返回格式一致）
     */
    private Map<String, Object> buildTaskSnapshot(TaskState state) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("task_id", state.taskId);
        snapshot.put("slice_id", state.sliceId);
        snapshot.put("status", state.status);
        snapshot.put("progress", state.progress);
        snapshot.put("error_msg", state.errorMsg);
        snapshot.put("extract_type", state.extractType);
        snapshot.put("topics", state.topics);
        snapshot.put("created_at", state.createdAt);
        snapshot.put("expires_at", state.expiresAt);
        snapshot.put("result_path", state.resultPath);
        return snapshot;
    }

    /**
     * 将任务终态元数据持久化到存储
     * <p>
     * 存储引用: tasks/{taskId}.json（local → bagDir/.tasks/{taskId}.json，minio → 对象存储）
     * 写入时机: 任务进入 READY 或 ERROR 状态时
     * 作用: 保障服务重启后仍能查询到已完成任务的信息
     */
    private void persistTaskMetadata(TaskState state) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("task_id", state.taskId);
            meta.put("slice_id", state.sliceId);
            meta.put("status", state.status);
            meta.put("progress", state.progress);
            meta.put("error_msg", state.errorMsg);
            meta.put("extract_type", state.extractType);
            meta.put("topics", state.topics);
            meta.put("result_path", state.resultPath);
            meta.put("created_at", state.createdAt);
            meta.put("expires_at", state.expiresAt);
            meta.put("completed_at", state.completedAt);

            storageAccess.put(StorageKeys.task(state.taskId),
                    objectMapper.writeValueAsBytes(meta), "application/json");
            log.debug("Persisted task metadata: taskId={}, status={}", state.taskId, state.status);
        } catch (Exception e) {
            log.warn("Failed to persist task metadata: taskId={}", state.taskId, e);
        }
    }

    @Override
    public Map<String, Object> recoverTaskFromDisk(String taskId) {
        // 安全校验：taskId 来自用户可控的 URL 路径参数（GET /v1/trajectory-tasks/{taskId} 等），
        // 必须为 UUID 格式。key 空间固定为 tasks/{taskId}.json（local → bagDir/.tasks），
        // UUID 校验通过后无路径遍历面（key 中不含用户可控分隔符）。
        if (taskId == null || !UUID_PATTERN.matcher(taskId).matches()) {
            log.warn("recoverTaskFromDisk rejected invalid taskId (not a UUID): {}", taskId);
            return null;
        }
        try {
            String key = StorageKeys.task(taskId);
            if (!storageAccess.exists(key)) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = objectMapper.readValue(storageAccess.readString(key), Map.class);

            // 恢复后重新载入内存，避免后续查询反复读磁盘
            TaskState state = new TaskState();
            state.taskId = (String) meta.getOrDefault("task_id", taskId);
            state.sliceId = (String) meta.get("slice_id");
            state.status = (String) meta.get("status");
            state.progress = meta.get("progress") instanceof Number
                    ? ((Number) meta.get("progress")).intValue() : 0;
            state.errorMsg = (String) meta.get("error_msg");
            state.extractType = (String) meta.get("extract_type");
            Object topicsRaw = meta.get("topics");
            if (topicsRaw instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> topics = (List<String>) topicsRaw;
                state.topics = new ArrayList<>(topics);
            }
            state.resultPath = (String) meta.get("result_path");
            state.createdAt = (String) meta.get("created_at");
            state.expiresAt = (String) meta.get("expires_at");
            state.completedAt = (String) meta.get("completed_at");
            taskStore.put(taskId, state);

            log.info("Recovered task from disk: taskId={}, status={}", taskId, state.status);
            return buildTaskSnapshot(state);

        } catch (Exception e) {
            log.warn("Failed to recover task from disk: taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 定时清理过期任务（每 30 秒执行一次）
     * <p>
     * 任务在 READY/ERROR 状态保留 TASK_RETENTION_SECONDS 秒后从内存移除。
     * 磁盘上的元数据文件保留 diskRetentionHours 小时后清理。
     */
    void cleanupExpiredTasks() {
        try {
            Instant now = Instant.now();

            // 1. 清理内存中过期任务
            int before = taskStore.size();
            taskStore.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
            int removed = before - taskStore.size();
            if (removed > 0) {
                log.debug("Cleaned up {} expired tasks from memory (remaining: {})", removed, taskStore.size());
            }

            // 2. 清理磁盘上过期的任务元数据文件
            cleanupDiskMetadata(now);

        } catch (Exception e) {
            log.warn("Error during task cleanup", e);
        }
    }

    /**
     * 清理过期的任务元数据文件
     * <p>
     * 列出 tasks/ 前缀下全部元数据（local → bagDir/.tasks，minio → 对象存储），
     * 解析 JSON 中的 completed_at（替换原文件修改时间逻辑），超过 diskRetentionHours 则删除。
     * 防止任务元数据无限增长。
     */
    private void cleanupDiskMetadata(Instant now) {
        try {
            Duration retention = Duration.ofHours(diskRetentionHours);
            int deletedCount = 0;

            for (String key : storageAccess.list(StorageKeys.tasksPrefix())) {
                // 只处理任务元数据 JSON 文件（tasks/{uuid}.json）；跳过目录占位对象/非目标 key，
                // 避免对其 readString 得到 null InputStream 触发 NPE。
                if (!key.endsWith(".json")) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = objectMapper.readValue(storageAccess.readString(key), Map.class);
                    Object completedRaw = meta.get("completed_at");
                    if (!(completedRaw instanceof String completedAt) || completedAt.isBlank()) {
                        // 无 completed_at 元数据（非终态/旧格式）→ 保留，避免误删
                        continue;
                    }
                    Instant completed = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(completedAt));
                    if (completed.plus(retention).isBefore(now)) {
                        storageAccess.delete(key);
                        deletedCount++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to clean up task metadata file: {}", key, e);
                }
            }

            if (deletedCount > 0) {
                log.info("Cleaned up {} expired task metadata files (retention: {}h)",
                        deletedCount, diskRetentionHours);
            }
        } catch (Exception e) {
            log.warn("Error cleaning up task metadata", e);
        }
    }

    /**
     * 判断任务是否已过期（终态后超过保留时长）
     */
    private boolean isExpired(TaskState state, Instant now) {
        if (!"READY".equals(state.status) && !"ERROR".equals(state.status)) {
            return false;
        }
        if (state.completedAt == null) return false;
        try {
            Instant completed = Instant.from(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(state.completedAt));
            return Duration.between(completed, now).getSeconds() >= TASK_RETENTION_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 提取任务状态（内存存储 + 磁盘持久化）
     */
    private static class TaskState {
        String taskId;
        String sliceId;
        String status;
        int progress;
        String errorMsg;
        String extractType;
        /**
         * 任务计划提取的 topic 列表（视频/信号提取场景，用于幂等覆盖判断与可观测性）
         */
        List<String> topics;
        String resultPath;
        String createdAt = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String expiresAt;
        /**
         * 任务到达终态（READY/ERROR）的时间戳，UTC RFC3339
         */
        String completedAt;
        /**
         * 单次执行标志：同一 taskId 的重复 @Async 调用（并发 POST 复用场景）直接跳过
         */
        final AtomicBoolean started = new AtomicBoolean(false);
    }
}
