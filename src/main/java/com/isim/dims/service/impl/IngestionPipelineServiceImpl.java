package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.SceneExtractionResponse;
import com.isim.dims.entity.CfdiMeta;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.DataSliceTag;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.SceneExtractionTask;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.entity.SliceStats;
import com.isim.dims.mapper.CfdiMetaMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.SceneExtractionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.mapper.SliceStatsMapper;
import com.isim.dims.service.IngestionPipelineService;
import com.isim.dims.service.IngestionService;
import com.isim.dims.service.DashboardStatsPipelineService;
import com.isim.dims.service.SceneExtractionPipelineService;
import com.isim.dims.service.SimsTagCatalog;
import com.isim.dims.service.VisualizationPipelineService;
import com.isim.dims.service.VisualizationService;
import com.isim.dims.util.CfdiConstants;
import com.isim.dims.util.StorageKeys;
import com.isim.dims.util.PythonExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据导入流水线 Service 实现
 * <p>
 * 独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效。
 * 流水线步骤：PARSING → TAGGING → VIDEO_EXTRACTING → STATS_EXTRACTING → SCENE_EXTRACTING → READY
 *
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {

    private final DataSliceMapper dataSliceMapper;
    private final SensorStreamMapper sensorStreamMapper;
    private final DataSliceTagMapper dataSliceTagMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final CfdiMetaMapper cfdiMetaMapper;
    private final SimsTagCatalog simsTagCatalog;
    private final IngestionService ingestionService;
    private final PythonExecutor pythonExecutor;
    private final SceneExtractionPipelineService sceneExtractionPipelineService;
    private final VisualizationService visualizationService;
    private final StorageAccess storageAccess;
    /**
     * 驾驶舱统计提取流水线：仅 dims.storage.type=local 模式装配
     * （minio 模式停用 —— extract_dashboard_stats.py 写本地产物，minio 模式无本地 bagDir）。
     * 可选注入，null 时跳过 Step 4。
     */
    @Autowired(required = false)
    private DashboardStatsPipelineService dashboardStatsPipelineService;
    private final VisualizationPipelineService visualizationPipelineService;
    private final SliceStatsMapper sliceStatsMapper;
    private final SceneExtractionTaskMapper sceneExtractionTaskMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 等待 IngestionTask 的最大重试次数（30 秒超时 = 60 次 × 500ms）
     */
    private static final int WAIT_FOR_TASK_MAX_RETRIES = 60;

    /**
     * 每次重试的休眠间隔（毫秒）
     */
    private static final long WAIT_FOR_TASK_RETRY_INTERVAL_MS = 500L;

    /**
     * 同步等待异步提取（视频/统计/场景）完成的最大时长（毫秒）= 1800s
     * 2026-08-14 由 600s 上调：11 路相机转码在冷 IO 下实测 ~15 分钟（TASK-013），
     * 600s 会先于任务完成超时置 slice ERROR，30 分钟为安全边界。
     */
    private static final long EXTRACTION_WAIT_TIMEOUT_MS = 1_800_000L;

    /**
     * 等待统计提取完成的最大时长（毫秒）= 600s。
     * 统计脚本自身超时 300s（DashboardStatsPipelineServiceImpl.PYTHON_TIMEOUT_SEC），
     * 等待取 2 倍余量。与 EXTRACTION_WAIT_TIMEOUT_MS（视频/场景）分离：
     * 统计是秒级轻量步骤且为软失败语义（见 waitForStatsExtraction），
     * 无需 30 分钟兜底 —— 挂死时尽快放行切片交付。
     */
    private static final long STATS_WAIT_TIMEOUT_MS = 600_000L;

    /**
     * 等待异步提取完成时的轮询间隔（毫秒）
     */
    private static final long EXTRACTION_POLL_INTERVAL_MS = 1000L;

    /**
     * auto_tag.py 超时（秒）。bag 规则按 topic 均匀采样解码，耗时随包体量增长：
     * 采样 200 帧时约 1GB/10s 量级，取 240s 给大包留余量（失败为软失败，不阻塞导入）。
     */
    private static final int AUTO_TAG_TIMEOUT_SECONDS = 240;

    /**
     * 会话目录名格式：{md5后8位}_{时间戳14-20位}_public
     * （与 LocalDirectoryImportServiceImpl.SESSION_DIR_PATTERN 一致，时间戳为北京时间 UTC+8）
     */
    private static final Pattern SESSION_ID_TS_PATTERN = Pattern.compile("^[0-9a-f]{8}_([0-9]{14,20})_public$");

    @Override
    @Async
    public void processParsePipeline(String sliceId, String filePath) {
        log.info("Async pipeline started: sliceId={}, filePath={}", sliceId, filePath);

        // 等待上传事务提交后查询 IngestionTask（避免 @Transactional + @Async 竞态）
        IngestionTask task = null;
        for (int retry = 0; retry < WAIT_FOR_TASK_MAX_RETRIES; retry++) {
            task = ingestionTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestionTask>().eq(IngestionTask::getSliceId, sliceId)
            );
            if (task != null) break;
            try {
                Thread.sleep(WAIT_FOR_TASK_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (task == null) {
            log.error("IngestionTask not found for sliceId={} after {} retries", sliceId, WAIT_FOR_TASK_MAX_RETRIES);
            return;
        }

        try {
            // ---- Step 1: PARSING ----
            ingestionService.updateTaskStatus(task, "PARSING", 30);

            // Python 进程只能消费本地文件：物化 bag 存储引用
            // （local 零拷贝解析为本地路径 / minio 下载到缓存，TTL 清理）
            String localBag = storageAccess.materializeLocal(filePath).toString();

            // ROSData 回放数据提取（extract_replay.py，api.md ROSData 顶层 JSON）。
            // 软失败语义：回放数据属辅助可视化产物，失败仅 warn，不阻塞导入流水线
            // （与驾驶舱统计 waitForStatsExtraction 的软失败语义一致）。
            extractReplayRosData(sliceId, localBag);

            Map<String, Object> parseArgs = new HashMap<>();
            parseArgs.put("bag_path", localBag);
            // utc_diff_us 只落库（详情页「UTC偏移」展示），不透传 parse_rosbag.py：
            // CFDI meta 的 utc_diff_us 是相对 trigger_timestamp_us（本地数据面触发时刻）的
            // 本地时钟→UTC 偏移（两者相加 = 事件真实 UTC 触发时间），而 bag 消息时间戳本身
            // 已接近 UTC；实测直接加上 ~56.5 年正偏移会生成 2083 年时间戳，触发
            // reconcileSessionTimestamps 错误覆盖 → 视频提取窗口偏差数小时 → "No video frames found"。
            // Python 侧保持 fallback：bag 原始时间戳作为 UTC，与 session 目录时间隔 <24h
            // 不会触发错误的 reconciliation。
            Long utcDiffUs = resolveUtcDiffUs(sliceId);
            // if (utcDiffUs != null) {
            //     parseArgs.put("utc_diff_us", utcDiffUs);
            // }
            String parseResult = pythonExecutor.execute("parse_rosbag.py", parseArgs, 300);

            // 解析元数据 JSON
            JsonNode metadata = objectMapper.readTree(parseResult);
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice == null) {
                throw new RuntimeException("DataSlice not found during parsing: " + sliceId);
            }

            // 更新 DataSlice 元数据
            LocalDateTime parseStartTs = metadata.has("start_ts")
                    ? LocalDateTime.parse(metadata.get("start_ts").asText(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    : null;
            LocalDateTime parseEndTs = metadata.has("end_ts")
                    ? LocalDateTime.parse(metadata.get("end_ts").asText(),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    : null;

            // LOCAL 导入兜底：会话目录时间戳与 parse 结果偏差 >24h 时用目录时间戳覆盖（WARN 不阻塞）
            LocalDateTime[] reconciled = reconcileSessionTimestamps(metadata, slice, parseStartTs, parseEndTs);
            if (reconciled[0] != null) {
                slice.setStartTs(reconciled[0]);
            }
            if (reconciled[1] != null) {
                slice.setEndTs(reconciled[1]);
            }
            if (metadata.has("duration_ms")) {
                slice.setDurationMs(metadata.get("duration_ms").asLong());
            }
            if (metadata.has("msg_count_total")) {
                slice.setMsgCountTotal(metadata.get("msg_count_total").asLong());
            }

            // 处理 GPS 包围盒
            if (metadata.has("gps_bbox") && !metadata.get("gps_bbox").isNull()) {
                JsonNode bbox = metadata.get("gps_bbox");
                if (bbox.has("min_lat")) slice.setGpsMinLat(bbox.get("min_lat").asDouble());
                if (bbox.has("min_lon")) slice.setGpsMinLon(bbox.get("min_lon").asDouble());
                if (bbox.has("max_lat")) slice.setGpsMaxLat(bbox.get("max_lat").asDouble());
                if (bbox.has("max_lon")) slice.setGpsMaxLon(bbox.get("max_lon").asDouble());
            }

            // 收集 sensor_types 并写入
            // 先清空该切片的既有 sensor_stream：保证重跑解析流水线幂等（不产生重复 topic 行），
            // 同时使历史切片可通过重跑重建为 bag 全量 topic 清单
            sensorStreamMapper.delete(new LambdaQueryWrapper<SensorStream>()
                    .eq(SensorStream::getSliceId, sliceId));

            Set<String> sensorTypeSet = new LinkedHashSet<>();
            if (metadata.has("streams") && metadata.get("streams").isArray()) {
                for (JsonNode streamNode : metadata.get("streams")) {
                    String sensorCategory = streamNode.has("sensor_category")
                            ? streamNode.get("sensor_category").asText()
                            : "other";
                    sensorTypeSet.add(sensorCategory);

                    // 创建 SensorStream 记录
                    SensorStream stream = new SensorStream();
                    stream.setSliceId(sliceId);
                    stream.setTopic(streamNode.has("topic") ? streamNode.get("topic").asText() : "");
                    stream.setMsgType(streamNode.has("msg_type") ? streamNode.get("msg_type").asText() : "");
                    stream.setSensorCategory(sensorCategory);
                    stream.setMsgCount(streamNode.has("msg_count") ? streamNode.get("msg_count").asInt() : 0);
                    if (streamNode.has("frequency_hz")) {
                        stream.setFrequencyHz(streamNode.get("frequency_hz").asDouble());
                    }
                    if (streamNode.has("first_ts")) {
                        stream.setFirstTs(LocalDateTime.parse(
                                streamNode.get("first_ts").asText(),
                                DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                    if (streamNode.has("last_ts")) {
                        stream.setLastTs(LocalDateTime.parse(
                                streamNode.get("last_ts").asText(),
                                DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                    if (streamNode.has("metadata")) {
                        stream.setMetadata(streamNode.get("metadata").toString());
                    }

                    // 从 topic 名推断 CFDI sensor_name（使用 CfdiConstants 中定义的映射）
                    // 未命中映射的非相机/非 latch topic 也必须入库：sensor_stream 即 bag 包的
                    // 全部 topic 清单（V1.1 可视化/回放的 topic 列表直接取自此表），
                    // sensor_name 仅在命中映射时写入（V3 迁移中该列可空）。
                    String topic = stream.getTopic();
                    if (topic != null && !topic.isEmpty()) {
                        String cfdiSensorId = CfdiConstants.getCfdiSensorIdFromTopic(topic);
                        if (cfdiSensorId != null && !cfdiSensorId.isBlank()) {
                            stream.setSensorName(cfdiSensorId);
                        }
                    }
                    sensorStreamMapper.insert(stream);
                }
            }
            slice.setSensorTypes(objectMapper.writeValueAsString(new ArrayList<>(sensorTypeSet)));

            // 持久化 utc_diff_us（CFDI meta 由 resolveUtcDiffUs 解析；缺失时为 null，
            // 不覆盖既有值——updateById 对 null 字段不写 SET，已有值保留）
            if (utcDiffUs != null) {
                slice.setUtcDiffUs(utcDiffUs);
            }
            slice.setStatus("TAGGING");
            dataSliceMapper.updateById(slice);

            // ---- Step 2: TAGGING ----
            ingestionService.updateTaskStatus(task, "TAGGING", 70);

            Map<String, Object> autoTagArgs = new HashMap<>();
            autoTagArgs.put("slice_id", sliceId);
            autoTagArgs.put("gps_bbox", buildGpsBboxMap(slice));
            autoTagArgs.put("start_ts", slice.getStartTs() != null
                    ? slice.getStartTs().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    : null);
            autoTagArgs.put("end_ts", slice.getEndTs() != null
                    ? slice.getEndTs().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    : null);
            autoTagArgs.put("sensor_types", new ArrayList<>(sensorTypeSet));
            // Python 侧 _apply_time_rule() 按采集地时区判定昼夜（采集车默认 UTC+8）
            autoTagArgs.put("tz_offset_hours", 8);
            // 透传本地 bag 路径：场景/车道线/信号灯/目标物/灯光规则必须解析 bag 才有证据，
            // 仅靠元数据只能产出「白天/夜间 → 低照明」「GPS/V2X」「中国」这几条。
            // localBag 由 Step 1 materializeLocal 物化，bag 规则的采样解码也发生在这里。
            autoTagArgs.put("bag_path", localBag);
            // 每类 topic 均匀采样帧数：长片段全量反序列化会顶到超时，200 帧足以判定占比类规则
            autoTagArgs.put("max_frames", 200);

            // 打标属辅助产物（与回放数据提取同语义）：失败仅告警，不阻塞导入流水线
            JsonNode tagResultJson = objectMapper.createObjectNode();
            try {
                String tagResult = pythonExecutor.execute("auto_tag.py", autoTagArgs, AUTO_TAG_TIMEOUT_SECONDS);
                tagResultJson = objectMapper.readTree(tagResult);
                log.debug("Auto-tag python output for sliceId={}: {}", sliceId, tagResult);
            } catch (Exception e) {
                log.warn("Auto-tag python failed for sliceId={}, tagging skipped: {}", sliceId, e.getMessage());
            }

            // ---- 新格式：tag_names（中文标签名），按名称查 SIMS 词表拿真实 UUID ----
            // Python 侧 auto_tag.py v2 输出 { "tag_names": ["白天", "夜间"] }
            // 旧版本输出 { "tag_ids": [ "<uuid>" ] }，两套都兼容
            Set<String> insertedTagIds = new LinkedHashSet<>(); // 去重：同一标签名+ID 只插一次

            // 优先处理新格式（tag_names，按名称查 SIMS 词表）
            if (tagResultJson.has("tag_names") && tagResultJson.get("tag_names").isArray()) {
                for (JsonNode nameNode : tagResultJson.get("tag_names")) {
                    String tagName = nameNode.asText();
                    if (tagName == null || tagName.isBlank()) continue;
                    com.isim.dims.dto.TagRef ref = simsTagCatalog.findByName(tagName);
                    if (ref != null && ref.getId() != null && !ref.getId().isBlank()) {
                        insertAutoTag(sliceId, ref.getId(), insertedTagIds);
                        log.info("Auto-tag matched name '{}' → id={}", tagName, ref.getId());
                    } else {
                        log.warn("Auto-tag name '{}' not found in SIMS catalog, skipped. " +
                                "切片 {} 不会因该规则打标签 —— 请确认 SIMS 片段域标签库是否存在此名称", tagName, sliceId);
                    }
                }
            }

            // 兼容旧格式：tag_ids（硬编码 UUID，直接查 SIMS 词表验证）
            if (tagResultJson.has("tag_ids") && tagResultJson.get("tag_ids").isArray()) {
                for (JsonNode tagIdNode : tagResultJson.get("tag_ids")) {
                    String tagId = tagIdNode.asText();
                    if (tagId == null || tagId.isBlank()) continue;
                    if (tagExists(tagId)) {
                        insertAutoTag(sliceId, tagId, insertedTagIds);
                        log.info("Auto-tag matched legacy id={}", tagId);
                    } else {
                        log.warn("Auto-tag legacy tag_id={} not found in SIMS catalog, skipped", tagId);
                    }
                }
            }

            log.info("Auto-tagging done for sliceId={}, applied {} unique tag(s)", sliceId, insertedTagIds.size());
            if (insertedTagIds.isEmpty()) {
                log.warn("Auto-tagging applied 0 tag(s) for sliceId={}; 检查 auto_tag.py 输出、" +
                        "bag_path 是否可读、以及名称是否命中 SIMS 片段域词表", sliceId);
            }

            // ---- Step 3: VIDEO_EXTRACTING（相机视频段提取，进度 75）----
            // 相机 topic 合并为单次提取任务（pipeline 内部按 topic 并行转码）。
            slice.setStatus("VIDEO_EXTRACTING");
            dataSliceMapper.updateById(slice);
            ingestionService.updateTaskStatus(task, "VIDEO_EXTRACTING", 75);

            List<String> cameraTopics = new ArrayList<>();
            List<SensorStream> cameraStreams = sensorStreamMapper.selectList(
                    new LambdaQueryWrapper<SensorStream>()
                            .eq(SensorStream::getSliceId, sliceId)
                            .eq(SensorStream::getSensorCategory, "camera")
                            .orderByAsc(SensorStream::getTopic)
            );
            for (SensorStream stream : cameraStreams) {
                if (stream.getTopic() != null && !stream.getTopic().isBlank()) {
                    cameraTopics.add(stream.getTopic());
                }
            }
            if (!cameraTopics.isEmpty()) {
                ExtractRequest videoRequest = new ExtractRequest();
                videoRequest.setTopics(cameraTopics);
                // 时间窗口取切片解析出的起止时间（UTC RFC3339，
                // extract_video_segment.py 内部换算为纳秒时间戳）
                if (slice.getStartTs() != null) {
                    videoRequest.setStartTs(slice.getStartTs()
                            .atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }
                if (slice.getEndTs() != null) {
                    videoRequest.setEndTs(slice.getEndTs()
                            .atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }
                Map<String, String> videoResp = visualizationService.extractVideoSegment(sliceId, videoRequest);
                waitForVideoExtraction(videoResp.get("task_id"));
            } else {
                log.info("No camera streams found, video extraction skipped: sliceId={}", sliceId);
            }

            // ---- Step 4: STATS_EXTRACTING（驾驶舱统计提取，进度 85）----
            slice.setStatus("STATS_EXTRACTING");
            dataSliceMapper.updateById(slice);
            ingestionService.updateTaskStatus(task, "STATS_EXTRACTING", 85);

            // 统计提取 local/minio 双模式均装配（2026-08-14 A6 修复后）；null 仅作防御。
            // 软失败语义：统计属驾驶舱辅助指标，FAILED/超时不阻塞切片 READY，
            // 异常由前端 has_alerts 徽标（statsStatus=FAILED）呈现。
            if (dashboardStatsPipelineService != null) {
                dashboardStatsPipelineService.triggerExtraction(sliceId);
                waitForStatsExtraction(sliceId);
            } else {
                log.info("Dashboard stats extraction skipped (service not available): sliceId={}", sliceId);
            }

            // ---- Step 5: SCENE_EXTRACTING（场景提取，进度 95）----
            slice.setStatus("SCENE_EXTRACTING");
            dataSliceMapper.updateById(slice);
            ingestionService.updateTaskStatus(task, "SCENE_EXTRACTING", 95);

            SceneExtractionResponse sceneResp = sceneExtractionPipelineService.triggerExtraction(sliceId);
            waitForSceneExtraction(sceneResp.getTaskId());

            // ---- Step 6: READY（仅前三步全部成功才置 READY，进度 100）----
            slice.setStatus("READY");
            dataSliceMapper.updateById(slice);
            ingestionService.updateTaskStatus(task, "READY", 100);

            // ---- Step 7: Kafka 事件日志 ----
            log.info("Event: dims.slice.created.v1, sliceId={}, name={}, status=READY, " +
                            "sensor_types={}, tags={}, start_ts={}, end_ts={}, duration_ms={}, msg_count_total={}",
                    sliceId, slice.getName(), sensorTypeSet,
                    tagResultJson.has("tag_ids") ? tagResultJson.get("tag_ids") : "[]",
                    slice.getStartTs(), slice.getEndTs(), slice.getDurationMs(), slice.getMsgCountTotal());

            log.info("Async pipeline completed: sliceId={}", sliceId);

        } catch (Exception e) {
            log.error("Async pipeline FAILED for sliceId={}", sliceId, e);

            // 更新切片的错误状态
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice != null) {
                slice.setStatus("WORN");
                slice.setErrorMsg(e.getMessage());
                dataSliceMapper.updateById(slice);
            }

            // 更新任务错误状态
            if (task != null) {
                task.setErrorMsg(e.getMessage());
                ingestionService.updateTaskStatus(task, "ERROR", 0);
            }
        }
    }

    /**
     * 提取 ROSData 回放数据（extract_replay.py）并按现有产物风格落盘。
     *
     * <p>产物 key 规范：replay/{sliceId}/replay/relay_plus/rosdata.json
     * （StorageKeys.replayRosData）。
     * minio 模式先写 staging 临时目录，成功后 put 上传对象存储；
     * local 模式 Python 直接写最终位置（零拷贝），与 trajectory 提取行为一致。</p>
     *
     * <p>软失败语义：任何异常仅记录 warn，不向上抛出 —— 回放数据缺失
     * 不应报废已完成的转换/视频/场景链路。</p>
     */
    private void extractReplayRosData(String sliceId, String localBag) {
        try {
            // 输出引用（key 规范 replay/{sliceId}/replay/relay_plus/rosdata.json；
            // minio 模式先写 staging（同 relay_plus 目录结构），成功后上传对象存储）
            String outputKey = StorageKeys.replayRosData(sliceId);
            Path outputPath = storageAccess.isMinioMode()
                    ? storageAccess.stageDir("replay").resolve(sliceId)
                    .resolve("relay_plus").resolve("rosdata.json")
                    : storageAccess.resolveLocalPath(outputKey);

            Map<String, Object> args = new HashMap<>();
            args.put("bag_path", localBag);
            args.put("output_path", outputPath.toString());
            args.put("excel_path", "/data/excel/ros_data.xlsx");
            args.put("timestamp_unit", "ns");

            // 全量消息解析（目标物/车道线/规划轨迹/信号灯等，2 Hz × 全时长），
            // 耗时高于 parse_rosbag，超时取 3600s
            String result = pythonExecutor.execute("extract_replay.py", args, 3600);

            Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);
            if (!Boolean.TRUE.equals(resultMap.get("success"))) {
                throw new RuntimeException("extract_replay.py failed: " + result);
            }
            // 产物契约校验：脚本必须产出 rosdata.json，否则视为失败
            if (!Files.exists(outputPath)) {
                throw new RuntimeException(
                        "extract_replay.py did not produce rosdata.json at " + outputPath);
            }

            // minio 模式：staging 产物上传对象存储（local 模式 Python 已直接写最终位置）
            if (storageAccess.isMinioMode()) {
                storageAccess.put(outputKey, outputPath, "application/json");
            }

            log.info("Replay ROSData extraction completed: sliceId={}, key={}, messages={}",
                    sliceId, outputKey, resultMap.get("messages"));
        } catch (Exception e) {
            log.warn("Replay ROSData extraction failed (soft-fail, pipeline continues): sliceId={}, err={}",
                    sliceId, e.getMessage());
        }
    }

    /**
     * 目录导入兜底：parse_rosbag.py 解析出的 start_ts/end_ts 与会话目录时间戳
     * （{md5后8位}_{时间戳14-20位}_public，时间戳为北京时间 UTC+8）偏差超过 24 小时时，
     * 用目录时间戳覆盖 start_ts，end_ts 按 bag 时长平移。仅 WARN 日志，不阻塞流水线。
     *
     * <p>判别依据：session_id 仅目录导入写入（文件上传不设置），
     * 不再依赖 source_type（V19 后其为数据来源提供方 MMT/ZYT/Hirain）。</p>
     *
     * @return 覆盖后的 [startTs, endTs]（UTC 表示），未覆盖时原样返回
     */
    private LocalDateTime[] reconcileSessionTimestamps(JsonNode metadata, DataSlice slice,
                                                       LocalDateTime parseStartTs, LocalDateTime parseEndTs) {
        if (parseStartTs == null || slice.getSessionId() == null) {
            return new LocalDateTime[]{parseStartTs, parseEndTs};
        }
        Matcher m = SESSION_ID_TS_PATTERN.matcher(slice.getSessionId());
        if (!m.matches()) {
            log.warn("Unexpected LOCAL session_id format, skip ts reconciliation: sliceId={}, sessionId={}",
                    slice.getId(), slice.getSessionId());
            return new LocalDateTime[]{parseStartTs, parseEndTs};
        }
        String tsDigits = m.group(1);
        LocalDateTime dirStart;
        try {
            dirStart = LocalDateTime.parse(tsDigits.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        } catch (DateTimeParseException e) {
            log.warn("Invalid session dir timestamp, skip ts reconciliation: sliceId={}, sessionId={}, ts={}",
                    slice.getId(), slice.getSessionId(), tsDigits);
            return new LocalDateTime[]{parseStartTs, parseEndTs};
        }
        // 目录时间戳视为北京时间（UTC+8），parse 结果为 UTC
        Instant dirStartUtc = dirStart.atZone(ZoneOffset.ofHours(8)).toInstant();
        Instant parseStartUtc = parseStartTs.toInstant(ZoneOffset.UTC);
        if (Duration.between(dirStartUtc, parseStartUtc).abs().compareTo(Duration.ofHours(24)) <= 0) {
            return new LocalDateTime[]{parseStartTs, parseEndTs};
        }
        log.warn("Session dir timestamp deviates from parse result by >24h, overriding with dir timestamp: "
                        + "sliceId={}, sessionId={}, dirStartUtc={}, parseStart={}, parseEnd={}",
                slice.getId(), slice.getSessionId(), dirStartUtc, parseStartTs, parseEndTs);
        LocalDateTime effectiveStart = LocalDateTime.ofInstant(dirStartUtc, ZoneOffset.UTC);
        LocalDateTime effectiveEnd = null;
        if (parseEndTs != null) {
            long durationMs = metadata.has("duration_ms") ? metadata.get("duration_ms").asLong()
                    : Duration.between(parseStartUtc, parseEndTs.toInstant(ZoneOffset.UTC)).toMillis();
            effectiveEnd = effectiveStart.plus(durationMs, ChronoUnit.MILLIS);
        }
        return new LocalDateTime[]{effectiveStart, effectiveEnd};
    }

    /**
     * DataSlice 实体 → GPS 包围盒 Map（供 Python 脚本调用）
     */
    private Map<String, Object> buildGpsBboxMap(DataSlice slice) {
        Map<String, Object> bbox = new HashMap<>();
        bbox.put("min_lat", slice.getGpsMinLat());
        bbox.put("min_lon", slice.getGpsMinLon());
        bbox.put("max_lat", slice.getGpsMaxLat());
        bbox.put("max_lon", slice.getGpsMaxLon());
        return bbox;
    }

    /**
     * 从 cfdi_meta.meta_data JSON 解析 utc_diff_us（单位：微秒）。
     *
     * <p>数据源：CFDI meta 文件根级字段 utc_diff_us，由 parse_cfdi_metadata.py
     * 以 meta_data dict 形式落库到 cfdi_meta.meta_data。
     * 真实 UTC = bag 时间戳 + utc_diff_us × 1000（us → ns）。</p>
     *
     * <p>返回 null 表示无有效值（非 CFDI 来源 / meta 缺失 / 解析失败），
     * 调用方跳过该参数，Python 侧自动 fallback。</p>
     */
    private Long resolveUtcDiffUs(String sliceId) {
        try {
            CfdiMeta cfdiMeta = cfdiMetaMapper.selectOne(
                    new LambdaQueryWrapper<CfdiMeta>().eq(CfdiMeta::getSliceId, sliceId)
            );
            if (cfdiMeta == null || cfdiMeta.getMetaData() == null || cfdiMeta.getMetaData().isEmpty()) {
                log.debug("No cfdi_meta.meta_data for sliceId={}, utc_diff_us skipped", sliceId);
                return null;
            }

            JsonNode metaData = objectMapper.readTree(cfdiMeta.getMetaData());
            JsonNode utcDiffNode = metaData.get("utc_diff_us");
            if (utcDiffNode == null || utcDiffNode.isNull() || !utcDiffNode.canConvertToLong()) {
                log.debug("No valid utc_diff_us in meta_data for sliceId={}", sliceId);
                return null;
            }
            return utcDiffNode.asLong();
        } catch (Exception e) {
            log.warn("Failed to resolve utc_diff_us for sliceId={}, skipping: {}", sliceId, e.getMessage());
            return null;
        }
    }

    /**
     * 检查标签是否存在
     */
    private boolean tagExists(String tagId) {
        // 本地 tag 表已随 V29 移除：标签有效性改判 SIMS 词表缓存
        // （auto_tag.py 返回的 tag_id 若不在片段域词表内则丢弃，避免落库悬空引用）
        return simsTagCatalog.find(tagId) != null;
    }

    /**
     * 幂等插入一条 auto 来源的切片标签关联行。
     * 同一 tagId 只插一次（Set 去重），避免新格式 tag_names + 旧格式 tag_ids 同时命中时重复落库。
     */
    private void insertAutoTag(String sliceId, String tagId, Set<String> insertedTagIds) {
        if (!insertedTagIds.add(tagId)) {
            return; // 已插入过，跳过
        }
        DataSliceTag dst = new DataSliceTag();
        dst.setSliceId(sliceId);
        dst.setTagId(tagId);
        dst.setSource("auto");
        dataSliceTagMapper.insert(dst);
    }

    // ========================================================================
    // 同步等待异步提取完成（视频/统计/场景，任一失败或超时 → 抛异常置 ERROR）
    // ========================================================================

    /**
     * 等待视频段提取任务完成（轮询 taskStore，任务清理后回退磁盘恢复）
     *
     * @throws RuntimeException 提取失败、超时或等待被中断
     */
    private void waitForVideoExtraction(String taskId) {
        long deadline = System.currentTimeMillis() + EXTRACTION_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> snapshot = visualizationPipelineService.getVideoExtractionTask(taskId);
            if (snapshot != null) {
                String status = (String) snapshot.get("status");
                if ("READY".equals(status)) {
                    log.info("Video extraction completed: taskId={}", taskId);
                    return;
                }
                if ("ERROR".equals(status)) {
                    throw new RuntimeException("Video extraction failed: taskId=" + taskId
                            + ", err=" + snapshot.get("error_msg"));
                }
            }
            if (interruptedWhileWaiting()) {
                throw new RuntimeException("Video extraction wait interrupted: taskId=" + taskId);
            }
        }
        throw new RuntimeException("Video extraction timed out after " + EXTRACTION_WAIT_TIMEOUT_MS
                + "ms: taskId=" + taskId);
    }

    /**
     * 等待驾驶舱统计提取完成（轮询 slice_stats 表）。
     *
     * <p>软失败语义（2026-08-14 起，部署考虑）：统计属驾驶舱辅助指标，
     * FAILED/超时仅记录 warn 并返回，切片仍可 READY —— 异常由前端
     * has_alerts 徽标（statsStatus=FAILED）呈现。历史行为（抛异常 →
     * 切片 ERROR）在生产不友好：秒级统计脚本失败会报废整条已完成的
     * 转换/视频/场景链路。等待被中断仍抛异常（流水线取消语义）。</p>
     */
    private void waitForStatsExtraction(String sliceId) {
        long deadline = System.currentTimeMillis() + STATS_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            SliceStats stats = sliceStatsMapper.selectOne(
                    new LambdaQueryWrapper<SliceStats>().eq(SliceStats::getSliceId, sliceId));
            if (stats != null) {
                String status = stats.getStatus();
                if ("SUCCESS".equals(status)) {
                    log.info("Dashboard stats extraction completed: sliceId={}", sliceId);
                    return;
                }
                if ("FAILED".equals(status)) {
                    log.warn("Dashboard stats extraction failed (soft-fail, slice continues): sliceId={}, err={}",
                            sliceId, stats.getErrorMsg());
                    return;
                }
            }
            if (interruptedWhileWaiting()) {
                throw new RuntimeException("Dashboard stats extraction wait interrupted: sliceId=" + sliceId);
            }
        }
        log.warn("Dashboard stats extraction timed out after {}ms (soft-fail, slice continues): sliceId={}",
                STATS_WAIT_TIMEOUT_MS, sliceId);
    }

    /**
     * 等待场景提取完成（轮询 scene_extraction_task 表）
     *
     * @throws RuntimeException 提取失败、超时或等待被中断
     */
    private void waitForSceneExtraction(String taskId) {
        long deadline = System.currentTimeMillis() + EXTRACTION_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            SceneExtractionTask sceneTask = sceneExtractionTaskMapper.selectById(taskId);
            if (sceneTask != null) {
                String status = sceneTask.getStatus();
                if ("SUCCESS".equals(status)) {
                    log.info("Scene extraction completed: taskId={}, totalScenes={}",
                            taskId, sceneTask.getTotalScenes());
                    return;
                }
                if ("FAILED".equals(status)) {
                    throw new RuntimeException("Scene extraction failed: taskId=" + taskId
                            + ", err=" + sceneTask.getErrorMsg());
                }
            }
            if (interruptedWhileWaiting()) {
                throw new RuntimeException("Scene extraction wait interrupted: taskId=" + taskId);
            }
        }
        throw new RuntimeException("Scene extraction timed out after " + EXTRACTION_WAIT_TIMEOUT_MS
                + "ms: taskId=" + taskId);
    }

    /**
     * 轮询休眠；线程被中断时恢复中断标志并返回 true
     */
    private boolean interruptedWhileWaiting() {
        try {
            Thread.sleep(EXTRACTION_POLL_INTERVAL_MS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
