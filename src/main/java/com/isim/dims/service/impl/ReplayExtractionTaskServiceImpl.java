package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.dto.ReplayExtractionResponse;
import com.isim.dims.dto.ReplayExtractionTaskResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.ReplayExtractionTask;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ReplayExtractionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.ReplayExtractionPipelineService;
import com.isim.dims.service.ReplayExtractionTaskService;
import com.isim.dims.util.ReplayTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 回放提取任务管理 Service 实现
 *
 * <p>任务去重/串行核心逻辑（契约 v1.3.0 + docs/REPLAY-2D-DESIGN.md §5.4）：</p>
 * <ol>
 *   <li>同 (slice_id, type, topics, start_ts, end_ts) 进行中任务（PENDING|RUNNING）去重 → 返回同一 task_id</li>
 *   <li>同切片存在其他参数进行中任务 → 409 REPLAY_EXTRACT_IN_PROGRESS（按 slice 串行，
 *       避免 bag 多进程读竞争；不同切片可并行）</li>
 *   <li>同参任务已 FAILED → 允许重试 1 次（以最近一次 SUCCESS 为界计数）；超限抛 REPLAY_EXTRACT_FAILED</li>
 * </ol>
 *
 * <p>check-then-act 用按 slice 的锁（ConcurrentHashMap）串行化，防并发 POST 竞态。</p>
 * @feature: replay
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayExtractionTaskServiceImpl implements ReplayExtractionTaskService {

    /** obstacles|messages|perception|map（v1.4 新增 perception/map） */
    private static final Set<String> VALID_TYPES = Set.of("obstacles", "messages", "perception", "map");

    private final ReplayExtractionTaskMapper taskMapper;
    private final DataSliceMapper dataSliceMapper;
    private final SensorStreamMapper sensorStreamMapper;
    private final ReplayExtractionPipelineService replayExtractionPipelineService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 按 slice 的锁（同切片任务创建串行化，不同切片互不影响） */
    private final Map<String, LockEntry> sliceLocks = new ConcurrentHashMap<>();

    /** 锁条目数量上限：超过后清理闲置条目（W4：防止 map 永久驻留） */
    private static final int MAX_SLICE_LOCKS = 256;

    /** 锁条目闲置判定时长：30 分钟无访问即视为可清理（锁持有仅毫秒级，阈值留足余量） */
    private static final long LOCK_IDLE_NANOS = TimeUnit.MINUTES.toNanos(30);

    // ========================================================================
    // 创建（或复用）任务
    // ========================================================================

    @Override
    public ReplayExtractionResponse createTask(String sliceId, ReplayExtractionRequest request) {
        // 1. 切片存在性校验
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 2. 参数归一化（校验 + 默认值），产出去重键
        String type = normalizeType(request.getType());
        List<String> topics = resolveTopics(sliceId, type, request.getTopics());
        LocalDateTime startTs = resolveStart(slice, request.getStartTs());
        LocalDateTime endTs = resolveEnd(slice, request.getEndTs());
        if (!startTs.isBefore(endTs)) {
            throw new RuntimeException("INVALID_PARAMETER: start_ts must be before end_ts");
        }

        // 2.5 W3（契约 v1.3.1）：显式传入 topics 的合法性同步校验（创建任务前，
        //     避免无效任务占住按 slice 串行队列并浪费重试额度）：
        //     不在切片 topic 清单 → 404 REPLAY_TOPIC_NOT_FOUND。
        //     （v1.4 修正：不再按前缀排除 /momenta_public/*、/navigation/*——
        //     实测 frame_id 内嵌 JSON 业务负载，均含 payload）
        validateRequestedTopics(sliceId, topics);

        // 3. 同切片任务创建串行化（check-then-act 原子性）
        Object lock = getSliceLock(sliceId);
        synchronized (lock) {
            // 3.1 按 slice 串行：同切片已有进行中任务
            List<ReplayExtractionTask> inFlight = taskMapper.selectList(
                    new LambdaQueryWrapper<ReplayExtractionTask>()
                            .eq(ReplayExtractionTask::getSliceId, sliceId)
                            .in(ReplayExtractionTask::getStatus, "PENDING", "RUNNING")
                            .orderByDesc(ReplayExtractionTask::getCreatedAt)
            );
            if (!inFlight.isEmpty()) {
                // 同参 → 复用同一 task_id
                for (ReplayExtractionTask task : inFlight) {
                    if (sameParams(task, type, topics, startTs, endTs)) {
                        log.info("Reusing in-flight replay extraction task: taskId={}, sliceId={}, type={}",
                                task.getId(), sliceId, type);
                        return toTaskResponse(task);
                    }
                }
                // 跨参 → 409（按 slice 串行约束）
                throw new RuntimeException("REPLAY_EXTRACT_IN_PROGRESS: slice " + sliceId
                        + " has in-flight extraction task "
                        + inFlight.get(0).getId() + " (" + inFlight.get(0).getType() + "), "
                        + "same slice allows only one extraction task at a time");
            }

            // 3.2 FAILED 同参任务：允许重试 1 次（以最近 SUCCESS / 24h 前为界计数）
            ensureRetryAllowed(sliceId, type, topics, startTs, endTs);

            // 4. 创建 PENDING 任务
            ReplayExtractionTask task = new ReplayExtractionTask();
            task.setId(UUID.randomUUID().toString());
            task.setSliceId(sliceId);
            task.setType(type);
            task.setTopics(serializeTopics(topics));
            task.setStartTs(startTs);
            task.setEndTs(endTs);
            task.setStatus("PENDING");
            task.setProgress(0);
            taskMapper.insert(task);

            // 5. 委派异步流水线（独立 Bean @Async；插入已自动提交，行对异步线程可见）
            replayExtractionPipelineService.processExtraction(task, slice.getFilePath(), request);

            log.info("Replay extraction task created: taskId={}, sliceId={}, type={}, topics={}",
                    task.getId(), sliceId, type, topics);
            return toTaskResponse(task);
        }
    }

    // ========================================================================
    // 查询任务详情
    // ========================================================================

    @Override
    public ReplayExtractionTaskResponse getTask(String taskId) {
        ReplayExtractionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("TASK_NOT_FOUND: " + taskId);
        }
        return toTaskDetailResponse(task);
    }

    // ========================================================================
    // 按 slice 锁（W4：有上限 + 闲置清理，防永久驻留）
    // ========================================================================

    /**
     * 获取（或创建）slice 锁并刷新使用时间
     *
     * <p>锁内操作仅含 DB 查询/插入与异步派发（毫秒级），Python 提取在 @Async 线程中执行、
     * 不占用本锁；因此 30 分钟闲置阈值远超实际锁持有时间，清理无并发风险。</p>
     */
    private Object getSliceLock(String sliceId) {
        cleanupStaleLocks();
        LockEntry entry = sliceLocks.computeIfAbsent(sliceId, k -> new LockEntry());
        entry.lastUsedNanos = System.nanoTime();
        return entry.lock;
    }

    /**
     * 锁表超过上限时清理闲置条目（不触碰近期使用过的锁，无竞态）
     */
    private void cleanupStaleLocks() {
        if (sliceLocks.size() < MAX_SLICE_LOCKS) {
            return;
        }
        long now = System.nanoTime();
        sliceLocks.entrySet().removeIf(e -> now - e.getValue().lastUsedNanos > LOCK_IDLE_NANOS);
        if (sliceLocks.size() >= MAX_SLICE_LOCKS) {
            log.warn("sliceLocks size {} exceeds threshold {}; stale entries reaped on idle",
                    sliceLocks.size(), MAX_SLICE_LOCKS);
        }
    }

    /**
     * 锁条目：锁对象 + 最后使用时间（nanoTime）
     */
    private static final class LockEntry {
        final Object lock = new Object();
        volatile long lastUsedNanos = System.nanoTime();
    }

    // ========================================================================
    // 参数归一化与去重
    // ========================================================================

    private String normalizeType(String type) {
        if (type == null || !VALID_TYPES.contains(type)) {
            throw new RuntimeException("INVALID_PARAMETER: type must be 'obstacles' or 'messages', got=" + type);
        }
        return type;
    }

    /**
     * W3（契约 v1.3.1）：显式传入 topics 的合法性校验
     *
     * <p>空 topics（type=obstacles/perception/map 取默认集合）不校验；非空时逐条：
     * 不在切片 topic 清单 → REPLAY_TOPIC_NOT_FOUND（404）。
     * （v1.4 修正：不再有 REPLAY_TOPIC_NO_PAYLOAD 排除——/momenta_public/*、
     * /navigation/* 实测 frame_id 内嵌 JSON 业务负载。）</p>
     */
    private void validateRequestedTopics(String sliceId, List<String> topics) {
        if (topics == null || topics.isEmpty()) {
            return;
        }
        // 切片 topic 清单（sensor_stream）
        List<SensorStream> streams = sensorStreamMapper.selectList(
                new LambdaQueryWrapper<SensorStream>()
                        .eq(SensorStream::getSliceId, sliceId)
        );
        Set<String> knownTopics = new java.util.HashSet<>();
        for (SensorStream s : streams) {
            if (s.getTopic() != null && !s.getTopic().isBlank()) {
                knownTopics.add(s.getTopic());
            }
        }
        for (String topic : topics) {
            if (!knownTopics.contains(topic)) {
                throw new RuntimeException("REPLAY_TOPIC_NOT_FOUND: " + topic + " not in slice " + sliceId);
            }
        }
    }

    /**
     * 归一化 topic 列表（去重 + 排序，保证去重键稳定）
     *
     * <p>type=obstacles 且 topics 为空 → 默认全部雷达/超声波 topic
     * （契约：/perception/radar/*、/sensor/ultrasonic/*，按 sensor_category 查询）；
     * type=perception → 默认 /momenta_public/perception/* 中仅含 smooth_object 的 topic
     * （extract_perception.py 只解析 smooth_object 的 perception_fusion_objects_data 字段，
     * 白名单扩充后其他感知 schema 会静默产出空帧，必须收窄默认集合）；
     * type=map → 默认 /momenta_public/ddld/*（landmark_vis 地图）；
     * type=messages 且 topics 为空 → 400（契约必填）。</p>
     */
    private List<String> resolveTopics(String sliceId, String type, List<String> topics) {
        if (topics != null && !topics.isEmpty()) {
            List<String> cleaned = new ArrayList<>(new LinkedHashSet<>(topics));
            cleaned.sort(String::compareTo);
            return cleaned;
        }
        if ("messages".equals(type)) {
            throw new RuntimeException("INVALID_PARAMETER: topics is required when type=messages");
        }
        List<String> defaults;
        if ("perception".equals(type)) {
            defaults = defaultTopics(sliceId, s -> com.isim.dims.util.ReplayTopicUtil.isPerceptionObjectTopic(s.getTopic()));
            if (defaults.isEmpty()) {
                log.warn("Replay perception: no /momenta_public/perception/*smooth_object topics found for slice {}", sliceId);
            }
            return defaults;
        }
        if ("map".equals(type)) {
            defaults = defaultTopics(sliceId, s -> com.isim.dims.util.ReplayTopicUtil.isMapTopic(s.getTopic()));
            if (defaults.isEmpty()) {
                log.warn("Replay map: no /momenta_public/ddld/* topics found for slice {}", sliceId);
            }
            return defaults;
        }
        // obstacles 默认：全部 radar / ultrasonic topic
        defaults = defaultTopics(sliceId, s -> "radar".equals(s.getSensorCategory())
                || "ultrasonic".equals(s.getSensorCategory()));
        if (defaults.isEmpty()) {
            log.warn("Replay obstacles: no radar/ultrasonic topics found for slice {}", sliceId);
        }
        return defaults;
    }

    /**
     * 按条件筛选切片 topic 名（升序），供各提取类型默认集合使用
     */
    private List<String> defaultTopics(String sliceId, java.util.function.Predicate<SensorStream> filter) {
        List<SensorStream> streams = sensorStreamMapper.selectList(
                new LambdaQueryWrapper<SensorStream>()
                        .eq(SensorStream::getSliceId, sliceId)
                        .orderByAsc(SensorStream::getTopic)
        );
        return streams.stream()
                .filter(filter)
                .map(SensorStream::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .sorted()
                .toList();
    }

    private LocalDateTime resolveStart(DataSlice slice, String startTs) {
        if (startTs == null || startTs.isBlank()) {
            return slice.getStartTs() != null ? slice.getStartTs() : LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
        }
        return ReplayTimeUtil.parseLocalDateTime(startTs);
    }

    private LocalDateTime resolveEnd(DataSlice slice, String endTs) {
        if (endTs == null || endTs.isBlank()) {
            return slice.getEndTs() != null ? slice.getEndTs() : LocalDateTime.now(ZoneOffset.UTC);
        }
        return ReplayTimeUtil.parseLocalDateTime(endTs);
    }

    /**
     * 同参判定：type + topics（排序后）+ 起止时间（毫秒精度）全等
     */
    private boolean sameParams(ReplayExtractionTask task, String type, List<String> topics,
                               LocalDateTime startTs, LocalDateTime endTs) {
        if (!type.equals(task.getType())) {
            return false;
        }
        List<String> taskTopics = parseTopics(task.getTopics());
        if (!taskTopics.equals(topics)) {
            return false;
        }
        return ReplayTimeUtil.timeEquals(task.getStartTs(), startTs)
                && ReplayTimeUtil.timeEquals(task.getEndTs(), endTs);
    }

    /**
     * FAILED 重试上限校验：同参任务以最近一次 SUCCESS（或 24h 前）为界，
     * FAILED 数 ≥ 2 → 拒绝（契约：FAILED 允许重试 1 次）
     */
    private void ensureRetryAllowed(String sliceId, String type, List<String> topics,
                                    LocalDateTime startTs, LocalDateTime endTs) {
        LocalDateTime boundary = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        ReplayExtractionTask lastSuccess = taskMapper.selectOne(
                new LambdaQueryWrapper<ReplayExtractionTask>()
                        .eq(ReplayExtractionTask::getSliceId, sliceId)
                        .eq(ReplayExtractionTask::getType, type)
                        .eq(ReplayExtractionTask::getStatus, "SUCCESS")
                        .orderByDesc(ReplayExtractionTask::getCreatedAt)
                        .last("LIMIT 1")
        );
        if (lastSuccess != null && lastSuccess.getCreatedAt() != null
                && lastSuccess.getCreatedAt().isAfter(boundary)) {
            boundary = lastSuccess.getCreatedAt();
        }

        List<ReplayExtractionTask> failed = taskMapper.selectList(
                new LambdaQueryWrapper<ReplayExtractionTask>()
                        .eq(ReplayExtractionTask::getSliceId, sliceId)
                        .eq(ReplayExtractionTask::getType, type)
                        .eq(ReplayExtractionTask::getStatus, "FAILED")
                        .gt(ReplayExtractionTask::getCreatedAt, boundary)
        );
        long sameParamsFailed = failed.stream()
                .filter(t -> sameParams(t, type, topics, startTs, endTs))
                .count();
        if (sameParamsFailed >= 2) {
            // W2（契约 v1.3.1）：重试超限 = 永久失败，客户端不得再重试（HTTP 409，
            // 区别于 REPLAY_EXTRACT_IN_PROGRESS 的"可轮询等待"语义）
            throw new RuntimeException("REPLAY_EXTRACT_RETRY_EXCEEDED: same-params extraction task failed "
                    + sameParamsFailed + " times for slice " + sliceId + ", retry limit reached (1)");
        }
    }

    // ========================================================================
    // 转换
    // ========================================================================

    private ReplayExtractionResponse toTaskResponse(ReplayExtractionTask task) {
        ReplayExtractionResponse resp = new ReplayExtractionResponse();
        resp.setTaskId(task.getId());
        resp.setStatus(task.getStatus());
        return resp;
    }

    private ReplayExtractionTaskResponse toTaskDetailResponse(ReplayExtractionTask task) {
        ReplayExtractionTaskResponse resp = new ReplayExtractionTaskResponse();
        resp.setTaskId(task.getId());
        resp.setSliceId(task.getSliceId());
        resp.setType(task.getType());
        resp.setTopics(parseTopics(task.getTopics()));
        resp.setStartTs(ReplayTimeUtil.formatLocalDateTime(task.getStartTs()));
        resp.setEndTs(ReplayTimeUtil.formatLocalDateTime(task.getEndTs()));
        resp.setStatus(task.getStatus());
        resp.setProgress(task.getProgress());
        resp.setMessage(task.getMessage());
        resp.setCreatedAt(ReplayTimeUtil.formatLocalDateTime(task.getCreatedAt()));
        resp.setFinishedAt(ReplayTimeUtil.formatLocalDateTime(task.getFinishedAt()));
        // 产物路径摘要（SUCCESS 时有值）
        if (task.getOutputPath() != null && !task.getOutputPath().isBlank()) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("output_path", task.getOutputPath());
            resp.setOutputSummary(summary);
        }
        return resp;
    }

    private String serializeTopics(List<String> topics) {
        try {
            return objectMapper.writeValueAsString(topics);
        } catch (Exception e) {
            log.warn("Failed to serialize topics: {}", topics, e);
            return "[]";
        }
    }

    private List<String> parseTopics(String topicsJson) {
        if (topicsJson == null || topicsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(topicsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("Failed to parse topics JSON: {}", topicsJson, e);
            return List.of();
        }
    }
}
