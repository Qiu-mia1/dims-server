package com.isim.dims.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.ReplayExtractionTask;
import com.isim.dims.entity.SensorCalibration;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ReplayExtractionTaskMapper;
import com.isim.dims.mapper.SensorCalibrationMapper;
import com.isim.dims.service.ReplayExtractionPipelineService;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回放提取异步流水线 Service 实现
 *
 * <p>独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效。
 * 状态机：PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次，由 TaskService 控制）。</p>
 *
 * <p>缓存文件布局（docs/REPLAY-2D-DESIGN.md §5.3，产物根 = replayCacheDir/{sliceId}/replay）：</p>
 * <pre>
 * obstacles/
 *   index.json                      # 脚本写入: { calibrated, segments: [{segment_start_ns, segment_end_ns, file, count}] }
 *   segments/{start_ns}.json        # 脚本写入: 帧数组 [{ time_ns|stamp_ns, calibrated?, obstacles[] }]
 * perception/
 *   index.json                      # extract_perception.py 写入: { frame: "global", segments: [...] }
 *   segments/{start_ns}.json        # 脚本写入: 帧数组 [{ time_ns, time, frame, unit, objects[] }]（smooth_object，全局系）
 * map/
 *   index.json                      # extract_map.py 写入: { frame: "ego", segments: [...] }
 *   segments/{start_ns}.json        # 脚本写入: 帧数组 [{ time_ns, time, frame, unit, lane_boundaries[], road_boundaries[], stop_lines[] }]（ddld，ego 局部系）
 * messages/{topic_safe}/
 *   index.json                      # 本服务维护: { topic, type, total, start_ns, end_ns, files: [{start_ns, end_ns, file, count}] }
 *   data_{start_ns}.jsonl           # 脚本写入: 每行 { seq, stamp_ns, stamp, data, header_stamp_ns?, sensor_timestamp_us? }
 * calibration.json                  # 有标定时由本服务导出供脚本 ego 换算
 * </pre>
 *
 * <p>MinIO 模式：Python 产物先写入 tempDir/staging/replay/{sliceId}（staging），
 * 成功后 commitStagedDir 上传到对象存储（key 前缀 replay/{sliceId}/replay/），
 * task.output_path 存相对 key；local 模式 Python 直接写最终位置（零拷贝），行为与现状一致。</p>
 * @feature: replay
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayExtractionPipelineServiceImpl implements ReplayExtractionPipelineService {

    private static final long MESSAGE_CHUNK_NS = 10L * 60 * 1_000_000_000L; // 10 分钟/块
    private static final int MESSAGE_CHUNK_LIMIT = 50_000;                  // 单块最大消息数

    private final ReplayExtractionTaskMapper taskMapper;
    private final SensorCalibrationMapper sensorCalibrationMapper;
    private final DataSliceMapper dataSliceMapper;
    private final PythonExecutor pythonExecutor;
    private final StorageAccess storageAccess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dims.python.timeout-sec:300}")
    private int pythonTimeoutSec;

    // ========================================================================
    // 异步执行入口
    // ========================================================================

    @Override
    @Async
    public void processExtraction(ReplayExtractionTask task, String bagPath, ReplayExtractionRequest request) {
        String taskId = task.getId();
        log.info("Replay extraction pipeline started: taskId={}, sliceId={}, type={}", taskId, task.getSliceId(), task.getType());

        // 幂等抢占：仅 PENDING 任务可进入 RUNNING（并发 dispatch 只执行一次）
        ReplayExtractionTask latest = taskMapper.selectById(taskId);
        if (latest == null || !"PENDING".equals(latest.getStatus())) {
            log.warn("Replay extraction skipped (task not PENDING): taskId={}", taskId);
            return;
        }
        latest.setStatus("RUNNING");
        latest.setProgress(10);
        taskMapper.updateById(latest);

        try {
            // Python 只能消费本地路径：物化 bag（local 零拷贝 / minio 下载缓存）
            String localBag = storageAccess.materializeLocal(bagPath).toString();

            // 产物根：minio → staging 临时目录（每次任务先清空，避免陈旧产物混入提交）；
            //         local → replayCacheDir/{sliceId}/replay（Python 直接写最终位置）
            Path replayRoot = storageAccess.isMinioMode()
                    ? storageAccess.stageDir("replay").resolve(task.getSliceId())
                    : storageAccess.resolveLocalPath("replay/" + task.getSliceId() + "/replay");
            if (storageAccess.isMinioMode()) {
                storageAccess.deleteRecursively(replayRoot);
            }

            // CFDI meta 时钟偏移（微秒）：bag 索引时间是数据面本地时钟，
            // 真实 UTC = bag 时间戳 + utc_diff_us × 1000。切片的 start_ts/end_ts
            // 已含该偏移（parse_rosbag 阶段换算），提取时必须把同一偏移传给 Python，
            // 否则请求窗口与 bag 帧时间整体错开 → 静默 0 条。
            Long utcDiffUs = resolveUtcDiffUs(task.getSliceId());

            String outputPath;
            String summary;
            if ("obstacles".equals(task.getType())) {
                outputPath = extractObstacles(task, localBag, replayRoot, utcDiffUs);
                summary = "obstacles extracted: " + outputPath;
            } else if ("perception".equals(task.getType())) {
                outputPath = extractPerception(task, localBag, replayRoot, utcDiffUs);
                summary = "perception extracted: " + outputPath;
            } else if ("map".equals(task.getType())) {
                outputPath = extractMap(task, localBag, replayRoot, utcDiffUs);
                summary = "map extracted: " + outputPath;
            } else {
                outputPath = extractMessages(task, localBag, replayRoot, utcDiffUs);
                summary = "messages extracted: " + outputPath;
            }

            // 回写 SUCCESS 终态
            latest.setStatus("SUCCESS");
            latest.setProgress(100);
            latest.setOutputPath(outputPath);
            latest.setMessage(summary);
            latest.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(latest);
            log.info("Replay extraction completed: taskId={}, outputPath={}", taskId, outputPath);

        } catch (Exception e) {
            log.error("Replay extraction FAILED: taskId={}", taskId, e);
            latest.setStatus("FAILED");
            latest.setMessage(e.getMessage() != null
                    ? (e.getMessage().length() > 2000 ? e.getMessage().substring(0, 2000) : e.getMessage())
                    : "unknown error");
            latest.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(latest);
        }
    }

    /**
     * 产物提交 + 返回存储引用（minio: 相对 key；local: 绝对路径）
     */
    private String commitAndRef(ReplayExtractionTask task, Path replayRoot, Path outputDir) {
        storageAccess.commitStagedDir(replayRoot, "replay/" + task.getSliceId() + "/replay/");
        return storageAccess.isMinioMode()
                ? "replay/" + task.getSliceId() + "/replay/" + replayRoot.relativize(outputDir).toString().replace('\\', '/')
                : outputDir.toString();
    }

    // ========================================================================
    // 障碍物提取
    // ========================================================================

    private String extractObstacles(ReplayExtractionTask task, String bagPath, Path replayRoot, Long utcDiffUs) throws Exception {
        Path outputDir = replayRoot.resolve("obstacles");
        Files.createDirectories(outputDir);

        Map<String, Object> args = new HashMap<>();
        args.put("bag_path", bagPath);
        args.put("topics", parseTopics(task.getTopics()));
        args.put("start_ns", toNs(task.getStartTs()));
        args.put("end_ns", toNs(task.getEndTs()));
        args.put("output_dir", outputDir.toString());
        putUtcDiffUs(args, utcDiffUs);

        // 有标定 → 导出 calibration.json 供脚本 ego 系换算（minio: 写入 staging，随产物一并提交）
        String calibrationPath = exportCalibrationIfPresent(task.getSliceId(), replayRoot);
        if (calibrationPath != null) {
            args.put("calibration_path", calibrationPath);
        }

        String result = pythonExecutor.execute("extract_obstacles.py", args, pythonTimeoutSec);
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        if (!Boolean.TRUE.equals(resultMap.get("success"))) {
            throw new RuntimeException("extract_obstacles.py failed: " + result);
        }

        // 产物契约校验：脚本必须产出 index.json（分段清单），否则视为失败
        if (!Files.exists(outputDir.resolve("index.json"))) {
            throw new RuntimeException("extract_obstacles.py did not produce index.json in " + outputDir);
        }
        return commitAndRef(task, replayRoot, outputDir);
    }

    /**
     * 有标定时导出 {cache}/{sliceId}/replay/calibration.json（供 extract_obstacles.py 做 ego 换算）
     *
     * @return calibration.json 本地路径；无标定返回 null
     */
    private String exportCalibrationIfPresent(String sliceId, Path replayRoot) throws Exception {
        List<SensorCalibration> rows = sensorCalibrationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SensorCalibration>()
                        .eq(SensorCalibration::getSliceId, sliceId));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        ArrayNode sensors = objectMapper.createArrayNode();
        for (SensorCalibration row : rows) {
            ObjectNode sensor = objectMapper.createObjectNode();
            sensor.put("sensor_name", row.getSensorName());
            sensor.put("sensor_type", row.getSensorType());
            mergePose(sensor, row.getPoseJson());
            sensors.add(sensor);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("frame", "ego");
        root.put("unit", "m");
        root.set("sensors", sensors);

        Path calibFile = replayRoot.resolve("calibration.json");
        Files.createDirectories(calibFile.getParent());
        // 原子写：tmp → rename
        Path tmp = calibFile.resolveSibling(calibFile.getFileName() + ".tmp");
        objectMapper.writeValue(tmp.toFile(), root);
        Files.move(tmp, calibFile, StandardCopyOption.REPLACE_EXISTING);
        return calibFile.toString();
    }

    /**
     * 将 pose_json 的 x/y/z/yaw/pitch/roll 合并进 sensor 节点
     */
    private void mergePose(ObjectNode sensor, String poseJson) {
        if (poseJson == null || poseJson.isBlank()) {
            return;
        }
        try {
            JsonNode pose = objectMapper.readTree(poseJson);
            if (pose.isObject()) {
                for (String field : new String[]{"x", "y", "z", "yaw", "pitch", "roll"}) {
                    JsonNode v = pose.get(field);
                    if (v != null && v.isNumber()) {
                        sensor.put(field, v.asDouble());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse pose_json: {}", poseJson, e);
        }
    }

    // ========================================================================
    // 融合感知提取（smooth_object，/momenta_public/perception/fusion/smooth_object）
    // ========================================================================

    /**
     * 调用 extract_perception.py：解析 smooth_object 的 frame_id 内嵌 JSON，
     * 提取融合感知目标（全局系 WGS84 投影），产物 {replay}/perception/index.json +
     * segments/*.json（10s 分段，layout 与 obstacles 同构，frame=global）。
     */
    private String extractPerception(ReplayExtractionTask task, String bagPath, Path replayRoot, Long utcDiffUs) throws Exception {
        Path outputDir = replayRoot.resolve("perception");
        Files.createDirectories(outputDir);

        Map<String, Object> args = new HashMap<>();
        args.put("bag_path", bagPath);
        args.put("topics", parseTopics(task.getTopics()));
        args.put("start_ns", toNs(task.getStartTs()));
        args.put("end_ns", toNs(task.getEndTs()));
        args.put("output_dir", outputDir.toString());
        putUtcDiffUs(args, utcDiffUs);

        String result = pythonExecutor.execute("extract_perception.py", args, pythonTimeoutSec);
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        if (!Boolean.TRUE.equals(resultMap.get("success"))) {
            throw new RuntimeException("extract_perception.py failed: " + result);
        }
        // 产物契约校验：脚本必须产出 index.json（分段清单），否则视为失败
        if (!Files.exists(outputDir.resolve("index.json"))) {
            throw new RuntimeException("extract_perception.py did not produce index.json in " + outputDir);
        }
        return commitAndRef(task, replayRoot, outputDir);
    }

    // ========================================================================
    // ddld 地图提取（landmark_vis，/momenta_public/ddld/landmark_vis）
    // ========================================================================

    /**
     * 调用 extract_map.py：解析 landmark_vis 的 frame_id 内嵌 JSON，提取
     * 车道线/道路边界/停止线（ego 局部系），产物 {replay}/map/index.json +
     * segments/*.json（10s 分段，frame=ego）。
     */
    private String extractMap(ReplayExtractionTask task, String bagPath, Path replayRoot, Long utcDiffUs) throws Exception {
        Path outputDir = replayRoot.resolve("map");
        Files.createDirectories(outputDir);

        Map<String, Object> args = new HashMap<>();
        args.put("bag_path", bagPath);
        args.put("topics", parseTopics(task.getTopics()));
        args.put("start_ns", toNs(task.getStartTs()));
        args.put("end_ns", toNs(task.getEndTs()));
        args.put("output_dir", outputDir.toString());
        putUtcDiffUs(args, utcDiffUs);

        String result = pythonExecutor.execute("extract_map.py", args, pythonTimeoutSec);
        Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
        if (!Boolean.TRUE.equals(resultMap.get("success"))) {
            throw new RuntimeException("extract_map.py failed: " + result);
        }
        // 产物契约校验：脚本必须产出 index.json（分段清单），否则视为失败
        if (!Files.exists(outputDir.resolve("index.json"))) {
            throw new RuntimeException("extract_map.py did not produce index.json in " + outputDir);
        }
        return commitAndRef(task, replayRoot, outputDir);
    }

    // ========================================================================
    // Raw 消息提取（按 topic + 10 分钟窗口分块）
    // ========================================================================

    private String extractMessages(ReplayExtractionTask task, String bagPath, Path replayRoot, Long utcDiffUs) throws Exception {
        List<String> topics = parseTopics(task.getTopics());
        if (topics.isEmpty()) {
            throw new RuntimeException("INVALID_PARAMETER: topics is required when type=messages");
        }
        long startNs = toNs(task.getStartTs());
        long endNs = toNs(task.getEndTs());
        Path messagesDir = replayRoot.resolve("messages");
        Files.createDirectories(messagesDir);

        // 部分 topic 失败不阻断其他 topic（DESIGN §6.5：任务级汇总错误）
        Map<String, String> failures = new HashMap<>();
        for (String topic : topics) {
            try {
                extractMessageTopic(task, topic, bagPath, startNs, endNs, messagesDir, utcDiffUs);
            } catch (Exception e) {
                failures.put(topic, e.getMessage());
                log.error("Message topic extraction failed: taskId={}, topic={}", task.getId(), topic, e);
            }
        }
        if (failures.size() == topics.size()) {
            throw new RuntimeException("All message topics failed: " + failures);
        }
        if (!failures.isEmpty()) {
            log.warn("Message extraction completed with {} failed topic(s): taskId={}, failures={}",
                    failures.size(), task.getId(), failures);
        }
        return commitAndRef(task, replayRoot, messagesDir);
    }

    /**
     * 提取单个 topic 的 Raw 消息（按 10 分钟窗口分块执行脚本，块结果合并进 index.json）
     */
    private void extractMessageTopic(ReplayExtractionTask task, String topic, String bagPath,
                                     long startNs, long endNs, Path messagesDir, Long utcDiffUs) throws Exception {
        String topicSafe = topic.replace("/", "_").replace(".", "_");
        Path topicDir = messagesDir.resolve(topicSafe);
        Files.createDirectories(topicDir);

        String msgType = "unknown";
        long chunkStart = startNs;
        int chunkIndex = 0;
        List<ObjectNode> mergedFiles = new ArrayList<>();
        long totalCount = 0;
        long minStart = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;

        // 已有 index（增量追加窗口时保留旧文件）：
        // 经存储层读取（local → 最终目录；minio → 已提交对象存储，staging 每次任务已清空）
        String indexKey = StorageKeys.replayMessagesIndex(task.getSliceId(), topicSafe);
        if (storageAccess.exists(indexKey)) {
            try {
                JsonNode old = objectMapper.readTree(storageAccess.readString(indexKey));
                if (old.has("type")) {
                    msgType = old.get("type").asText();
                }
                if (old.has("files") && old.get("files").isArray()) {
                    for (JsonNode f : old.get("files")) {
                        mergedFiles.add((ObjectNode) f.deepCopy());
                        totalCount += f.path("count").asLong(0);
                        minStart = Math.min(minStart, f.path("start_ns").asLong(Long.MAX_VALUE));
                        maxEnd = Math.max(maxEnd, f.path("end_ns").asLong(Long.MIN_VALUE));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read existing message index, rebuilding: {}", indexKey, e);
            }
        }

        while (chunkStart < endNs) {
            long chunkEnd = Math.min(chunkStart + MESSAGE_CHUNK_NS, endNs);
            String fileName = "data_" + chunkStart + ".jsonl";
            Path outputFile = topicDir.resolve(fileName);

            Map<String, Object> args = new HashMap<>();
            args.put("bag_path", bagPath);
            args.put("topic", topic);
            args.put("start_ns", chunkStart);
            args.put("end_ns", chunkEnd);
            args.put("output_path", outputFile.toString());
            args.put("limit", MESSAGE_CHUNK_LIMIT);
            putUtcDiffUs(args, utcDiffUs);

            String result = pythonExecutor.execute("extract_raw_messages.py", args, pythonTimeoutSec);
            Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
            if (!Boolean.TRUE.equals(resultMap.get("success"))) {
                throw new RuntimeException("extract_raw_messages.py failed for topic " + topic + ": " + result);
            }
            if (!Files.exists(outputFile)) {
                throw new RuntimeException("extract_raw_messages.py did not produce output file: " + outputFile);
            }
            long count = resultMap.get("count") instanceof Number
                    ? ((Number) resultMap.get("count")).longValue()
                    : countJsonlLines(outputFile);
            String typeFromResult = resultMap.get("type") instanceof String
                    ? (String) resultMap.get("type") : null;
            if (typeFromResult != null) {
                msgType = typeFromResult;
            }

            // 合并文件清单（按 start_ns 去重：重跑同块覆盖旧文件，totalCount 先扣除旧计数）
            final long thisChunkStart = chunkStart;
            final long oldCount = mergedFiles.stream()
                    .filter(f -> f.path("start_ns").asLong() == thisChunkStart)
                    .mapToLong(f -> f.path("count").asLong(0))
                    .sum();
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("start_ns", chunkStart);
            entry.put("end_ns", chunkEnd);
            entry.put("file", fileName);
            entry.put("count", count);
            mergedFiles.removeIf(f -> f.path("start_ns").asLong() == thisChunkStart);
            mergedFiles.add(entry);
            totalCount += count - oldCount;
            minStart = Math.min(minStart, chunkStart);
            maxEnd = Math.max(maxEnd, chunkEnd);

            chunkIndex++;
            chunkStart = chunkEnd;
        }

        // 原子写 index.json
        ObjectNode index = objectMapper.createObjectNode();
        index.put("topic", topic);
        index.put("type", msgType);
        index.put("total", totalCount);
        index.put("start_ns", minStart == Long.MAX_VALUE ? startNs : minStart);
        index.put("end_ns", maxEnd == Long.MIN_VALUE ? endNs : maxEnd);
        ArrayNode filesNode = index.putArray("files");
        mergedFiles.sort((a, b) -> Long.compare(a.path("start_ns").asLong(), b.path("start_ns").asLong()));
        mergedFiles.forEach(filesNode::add);

        Path indexPath = topicDir.resolve("index.json");
        Path tmp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        objectMapper.writeValue(tmp.toFile(), index);
        Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private long countJsonlLines(Path file) {
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                count++;
            }
        } catch (Exception e) {
            log.warn("Failed to count lines of {}", file, e);
        }
        return count;
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private long toNs(LocalDateTime ldt) {
        Instant instant = ldt.toInstant(ZoneOffset.UTC);
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
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
            if (slice == null) {
                return null;
            }
            Long v = slice.getUtcDiffUs();
            log.info("Resolved utc_diff_us={} for sliceId={}", v, sliceId);
            return v;
        } catch (Exception e) {
            log.warn("Failed to load utc_diff_us for sliceId={} — falling back to auto calibration",
                    sliceId, e);
            return null;
        }
    }

    /** 将 utc_diff_us 写入 Python 入参（仅非 null 时写入）。 */
    private void putUtcDiffUs(Map<String, Object> args, Long utcDiffUs) {
        if (utcDiffUs != null) {
            args.put("utc_diff_us", utcDiffUs);
        }
    }
}
