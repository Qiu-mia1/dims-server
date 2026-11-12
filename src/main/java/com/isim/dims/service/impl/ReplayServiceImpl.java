package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.MapFrameResponse;
import com.isim.dims.dto.ObstacleFrameResponse;
import com.isim.dims.dto.ObstacleQueryResult;
import com.isim.dims.dto.PerceptionFrameResponse;
import com.isim.dims.dto.RawMessagePageResponse;
import com.isim.dims.dto.ReplayContextResponse;
import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.dto.ReplayFrameQueryResult;
import com.isim.dims.dto.SensorCalibrationSet;
import com.isim.dims.dto.VehicleSpec;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.DataSliceTag;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.CalibrationService;
import com.isim.dims.service.ReplayExtractionTaskService;
import com.isim.dims.service.ReplayService;
import com.isim.dims.service.SimsTagCatalog;
import com.isim.dims.util.ReplayTimeUtil;
import com.isim.dims.util.ReplayTopicUtil;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2D 回放 Service 实现（read-through 缓存编排）
 *
 * <p>缓存产物契约（与 dims-python 脚本 / 提取流水线的文件协议）：</p>
 * <pre>
 * {replayCacheDir}/{sliceId}/replay/obstacles/index.json
 *   { "calibrated": bool, "segments": [{ "segment_start_ns", "segment_end_ns", "file", "count" }] }
 * {replayCacheDir}/{sliceId}/replay/obstacles/segments/{start_ns}.json
 *   帧数组 [ { "time_ns"|"stamp_ns", "calibrated"?, "obstacles": [ObstacleTarget...] } ]
 * {replayCacheDir}/{sliceId}/replay/messages/{topic_safe}/index.json
 *   { "topic", "type", "total", "start_ns", "end_ns", "files": [{ "start_ns", "end_ns", "file", "count" }] }
 * {replayCacheDir}/{sliceId}/replay/messages/{topic_safe}/data_{start_ns}.jsonl
 *   每行 { "seq", "stamp_ns", "stamp", "data", "header_stamp_ns"?, "sensor_timestamp_us"? }
 * </pre>
 * @feature: replay
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayServiceImpl implements ReplayService {

    /** 相机 topic 模式：/sensor/camera_&lt;position&gt;/video */
    private static final Pattern CAMERA_TOPIC_PATTERN =
            Pattern.compile("^/sensor/camera_([a-z0-9_]+)/video$");

    /** 相机位置 → 显示名（契约 CameraPosition 枚举，禁用 F-number 别名） */
    private static final Map<String, String> CAMERA_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("front_wide", "前视广角"),
            Map.entry("front_far", "前视远焦"),
            Map.entry("front_fisheye", "前视鱼眼"),
            Map.entry("left_front", "左前"),
            Map.entry("right_front", "右前"),
            Map.entry("left_rear", "左后"),
            Map.entry("right_rear", "右后"),
            Map.entry("left_fisheye", "左鱼眼"),
            Map.entry("right_fisheye", "右鱼眼"),
            Map.entry("rear_fisheye", "后鱼眼"),
            Map.entry("rear_mid", "后中视")
    );

    /** 默认 4 路组合（契约 ReplayVideoStream.default 语义） */
    private static final Set<String> DEFAULT_4_CAMERAS =
            Set.of("front_wide", "front_far", "left_front", "right_front");

    /** 单帧目标上限（契约性能约束 ≤ 200，障碍目标与感知目标共用） */
    private static final int MAX_TARGETS_PER_FRAME = 200;

    private final DataSliceMapper dataSliceMapper;
    private final SensorStreamMapper sensorStreamMapper;
    private final DataSliceTagMapper dataSliceTagMapper;
    private final CalibrationService calibrationService;
    private final ReplayExtractionTaskService replayExtractionTaskService;
    private final StorageAccess storageAccess;
    private final SimsTagCatalog simsTagCatalog;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========================================================================
    // 回放上下文
    // ========================================================================

    @Override
    public ReplayContextResponse getContext(String sliceId) {
        DataSlice slice = requireSlice(sliceId);

        ReplayContextResponse resp = new ReplayContextResponse();
        resp.setSliceId(sliceId);
        resp.setStartTs(format(slice.getStartTs()));
        resp.setEndTs(format(slice.getEndTs()));
        resp.setDurationMs(slice.getDurationMs());
        resp.setVideoStreams(buildVideoStreams(sliceId));
        resp.setTopics(buildTopicInfos(sliceId));
        SensorCalibrationSet calibration = calibrationService.getCalibrationSet(sliceId);
        resp.setCalibration(calibration);
        resp.setVehicle(calibration.getVehicle() != null ? calibration.getVehicle() : defaultVehicle());
        resp.setTags(buildTagNames(sliceId));
        resp.setDataReady(isDataReady(sliceId));
        return resp;
    }

    // ========================================================================
    // 障碍物帧（read-through 缓存）
    // ========================================================================

    @Override
    public ObstacleQueryResult getObstacles(String sliceId, String time, Integer windowMs, String sensorTypes) {
        DataSlice slice = requireSlice(sliceId);

        TimeWindow tw = parseTimeWindow(time, windowMs);
        long timeNs = tw.timeNs();
        long windowNs = tw.windowNs();
        Set<String> requestedTypes = parseSensorTypes(sensorTypes);

        ObstacleQueryResult result = new ObstacleQueryResult();

        // 1. 读缓存段 → 命中直接返回 200（按 sensor_types 过滤目标类型）
        ObstacleFrameResponse frame = readObstacleFrame(sliceId, timeNs, windowNs);
        if (frame != null) {
            if (frame.getObstacles() != null) {
                frame.setObstacles(frame.getObstacles().stream()
                        .filter(t -> requestedTypes.contains(t.getType()))
                        .toList());
            }
            result.setFrame(frame);
            return result;
        }

        // 2. 未命中 → 触发/复用提取任务（202 + task_id）
        //    无雷达/超声波 topic 的切片无数据可提取，直接返回空帧（200）
        List<String> topics = obstacleTopics(sliceId);
        if (topics.isEmpty()) {
            log.info("Replay obstacles: slice {} has no radar/ultrasonic topics, returning empty frame", sliceId);
            // 无缓存上下文且无数据源：calibrated 置 false（无标定可言，仅展示占位）
            result.setFrame(emptyFrame(timeNs, false));
            return result;
        }

        ReplayExtractionRequest request = new ReplayExtractionRequest();
        request.setType("obstacles");
        request.setTopics(topics);
        request.setStartTs(format(slice.getStartTs()));
        request.setEndTs(format(slice.getEndTs()));

        // 同参进行中去重返回同一 task_id；跨参进行中抛 REPLAY_EXTRACT_IN_PROGRESS（409）
        // 提取任务固定覆盖全部雷达/超声波 topic（保证不同 sensor_types 请求去重键稳定）
        result.setTaskResponse(replayExtractionTaskService.createTask(sliceId, request));
        return result;
    }

    /**
     * 解析 sensor_types 参数（契约枚举 radar|ultrasonic；未知类型忽略；空 → 两者）
     */
    private Set<String> parseSensorTypes(String sensorTypes) {
        Set<String> types = new java.util.HashSet<>();
        if (sensorTypes != null && !sensorTypes.isBlank()) {
            for (String part : sensorTypes.split(",")) {
                String t = part.trim();
                if ("radar".equals(t) || "ultrasonic".equals(t)) {
                    types.add(t);
                }
            }
        }
        if (types.isEmpty()) {
            types.add("radar");
            types.add("ultrasonic");
        }
        return types;
    }

    // ========================================================================
    // 融合感知帧（read-through 缓存，frame=global）
    // ========================================================================

    @Override
    public ReplayFrameQueryResult<PerceptionFrameResponse> getPerception(String sliceId, String time, Integer windowMs) {
        DataSlice slice = requireSlice(sliceId);

        TimeWindow tw = parseTimeWindow(time, windowMs);
        long timeNs = tw.timeNs();
        long windowNs = tw.windowNs();

        ReplayFrameQueryResult<PerceptionFrameResponse> result = new ReplayFrameQueryResult<>();

        // 1. 读缓存段 → 命中直接返回 200
        PerceptionFrameResponse frame = readPerceptionFrame(sliceId, timeNs, windowNs);
        if (frame != null) {
            result.setFrame(frame);
            return result;
        }

        // 2. 未命中 → 触发/复用提取任务（202 + task_id）
        //    无感知 topic（/momenta_public/perception/*）的切片无数据可提取，直接返回空帧（200）
        List<String> topics = perceptionTopics(sliceId);
        if (topics.isEmpty()) {
            log.info("Replay perception: slice {} has no perception topics, returning empty frame", sliceId);
            result.setFrame(emptyPerceptionFrame(timeNs));
            return result;
        }

        ReplayExtractionRequest request = new ReplayExtractionRequest();
        request.setType("perception");
        request.setTopics(topics);
        request.setStartTs(format(slice.getStartTs()));
        request.setEndTs(format(slice.getEndTs()));

        // 同参进行中去重返回同一 task_id；跨参进行中抛 REPLAY_EXTRACT_IN_PROGRESS（409）
        // 提取任务固定覆盖全部感知 topic（保证去重键稳定）
        result.setTaskResponse(replayExtractionTaskService.createTask(sliceId, request));
        return result;
    }

    /**
     * 读感知缓存段：命中返回帧（窗口内最近一帧）；未命中返回 null（触发提取）
     *
     * <p>S1 防无限循环（与障碍一致）：index.json 存在且可解析 = 该切片已提取过，
     * 此时任何时刻查不到帧（数据间隙/时间越界/空索引 segments:[]）一律返回空帧 200，
     * 不再触发提取。</p>
     *
     * @return 帧响应（命中或空帧）；null 仅当无缓存上下文（触发重建提取）
     */
    private PerceptionFrameResponse readPerceptionFrame(String sliceId, long timeNs, long windowNs) {
        SegmentLookup lookup = locateSegment(sliceId, "perception", timeNs);
        if (lookup.segment() == null) {
            // index 存在但无覆盖 segment：数据间隙/时间越界/空索引 → 空帧 200
            return lookup.indexPresent() ? emptyPerceptionFrame(timeNs) : null;
        }
        JsonNode frames = readSegmentFrames(sliceId, "perception", lookup.segment());
        if (frames == null) {
            return null; // 分段文件缺失/损坏 → 触发重建提取
        }
        JsonNode best = findBestFrameInWindow(frames, timeNs, windowNs);
        if (best == null) {
            // 分段存在但窗口内无帧（数据间隙）→ 返回空帧（200），不算缓存未命中
            return emptyPerceptionFrame(timeNs);
        }
        return toPerceptionFrameResponse(best, frameStampNs(best));
    }

    private PerceptionFrameResponse toPerceptionFrameResponse(JsonNode frame, long stampNs) {
        PerceptionFrameResponse resp = new PerceptionFrameResponse();
        resp.setTime(ReplayTimeUtil.formatNs(stampNs));
        resp.setTimeNs(stampNs);
        resp.setFrame("global");
        resp.setUnit("m");

        List<PerceptionFrameResponse.PerceptionObject> objects = new ArrayList<>();
        JsonNode objectNodes = frame.path("objects");
        if (objectNodes.isArray()) {
            for (JsonNode node : objectNodes) {
                if (objects.size() >= MAX_TARGETS_PER_FRAME) {
                    break;
                }
                if (!node.isObject()) {
                    continue;
                }
                objects.add(toPerceptionObject(node));
            }
        }
        resp.setObjects(objects);
        return resp;
    }

    /**
     * 帧内感知目标 → 契约 PerceptionObject（容忍缺失字段，未知字段丢弃）
     */
    private PerceptionFrameResponse.PerceptionObject toPerceptionObject(JsonNode node) {
        PerceptionFrameResponse.PerceptionObject o = new PerceptionFrameResponse.PerceptionObject();
        JsonNode trackId = node.get("track_id");
        o.setTrackId(trackId != null && trackId.isNumber() ? trackId.asLong() : null);
        o.setClazz(node.path("class").asText(null));
        o.setConfidence(num(node, "confidence"));
        o.setLengthM(num(node, "length_m"));
        o.setWidthM(num(node, "width_m"));
        o.setHeightM(num(node, "height_m"));
        o.setX(num(node, "x"));
        o.setY(num(node, "y"));
        o.setZ(num(node, "z"));
        o.setVx(num(node, "vx"));
        o.setVy(num(node, "vy"));
        o.setHeadingYaw(num(node, "heading_yaw"));
        return o;
    }

    /**
     * 构造空感知帧（200，缓存命中但窗口/时段无数据，frame=global）
     */
    private PerceptionFrameResponse emptyPerceptionFrame(long timeNs) {
        PerceptionFrameResponse resp = new PerceptionFrameResponse();
        resp.setTime(ReplayTimeUtil.formatNs(timeNs));
        resp.setTimeNs(timeNs);
        resp.setFrame("global");
        resp.setUnit("m");
        resp.setObjects(List.of());
        return resp;
    }

    // ========================================================================
    // ddld 地图帧（read-through 缓存，frame=ego）
    // ========================================================================

    @Override
    public ReplayFrameQueryResult<MapFrameResponse> getMap(String sliceId, String time, Integer windowMs) {
        DataSlice slice = requireSlice(sliceId);

        TimeWindow tw = parseTimeWindow(time, windowMs);
        long timeNs = tw.timeNs();
        long windowNs = tw.windowNs();

        ReplayFrameQueryResult<MapFrameResponse> result = new ReplayFrameQueryResult<>();

        // 1. 读缓存段 → 命中直接返回 200
        MapFrameResponse frame = readMapFrame(sliceId, timeNs, windowNs);
        if (frame != null) {
            result.setFrame(frame);
            return result;
        }

        // 2. 未命中 → 触发/复用提取任务（202 + task_id）
        //    无地图 topic（/momenta_public/ddld/*）的切片无数据可提取，直接返回空帧（200）
        List<String> topics = mapTopics(sliceId);
        if (topics.isEmpty()) {
            log.info("Replay map: slice {} has no map topics, returning empty frame", sliceId);
            result.setFrame(emptyMapFrame(timeNs));
            return result;
        }

        ReplayExtractionRequest request = new ReplayExtractionRequest();
        request.setType("map");
        request.setTopics(topics);
        request.setStartTs(format(slice.getStartTs()));
        request.setEndTs(format(slice.getEndTs()));

        // 同参进行中去重返回同一 task_id；跨参进行中抛 REPLAY_EXTRACT_IN_PROGRESS（409）
        result.setTaskResponse(replayExtractionTaskService.createTask(sliceId, request));
        return result;
    }

    /**
     * 读地图缓存段：命中返回帧（窗口内最近一帧）；未命中返回 null（触发提取）
     *
     * <p>S1 防无限循环（与障碍一致）：index.json 存在且可解析 = 该切片已提取过，
     * 此时任何时刻查不到帧（数据间隙/时间越界/空索引 segments:[]）一律返回空帧 200，
     * 不再触发提取。</p>
     */
    private MapFrameResponse readMapFrame(String sliceId, long timeNs, long windowNs) {
        SegmentLookup lookup = locateSegment(sliceId, "map", timeNs);
        if (lookup.segment() == null) {
            // index 存在但无覆盖 segment：数据间隙/时间越界/空索引 → 空帧 200
            return lookup.indexPresent() ? emptyMapFrame(timeNs) : null;
        }
        JsonNode frames = readSegmentFrames(sliceId, "map", lookup.segment());
        if (frames == null) {
            return null; // 分段文件缺失/损坏 → 触发重建提取
        }
        JsonNode best = findBestFrameInWindow(frames, timeNs, windowNs);
        if (best == null) {
            // 分段存在但窗口内无帧（数据间隙）→ 返回空帧（200），不算缓存未命中
            return emptyMapFrame(timeNs);
        }
        return toMapFrameResponse(best, frameStampNs(best));
    }

    private MapFrameResponse toMapFrameResponse(JsonNode frame, long stampNs) {
        MapFrameResponse resp = new MapFrameResponse();
        resp.setTime(ReplayTimeUtil.formatNs(stampNs));
        resp.setTimeNs(stampNs);
        resp.setFrame("ego");
        resp.setUnit("m");
        resp.setLaneBoundaries(readMapLines(frame, "lane_boundaries"));
        resp.setRoadBoundaries(readMapLines(frame, "road_boundaries"));
        resp.setStopLines(readMapLines(frame, "stop_lines"));
        return resp;
    }

    /**
     * 帧内地图线要素 → 契约 MapLine（容忍缺失字段，未知字段丢弃；折线点序列原样透传）
     */
    private List<MapFrameResponse.MapLine> readMapLines(JsonNode frame, String field) {
        List<MapFrameResponse.MapLine> lines = new ArrayList<>();
        JsonNode nodes = frame.path(field);
        if (!nodes.isArray()) {
            return lines;
        }
        for (JsonNode node : nodes) {
            if (!node.isObject()) {
                continue;
            }
            MapFrameResponse.MapLine line = new MapFrameResponse.MapLine();
            line.setType(node.path("type").asText("unknown"));
            JsonNode typeValue = node.get("type_value");
            line.setTypeValue(typeValue != null && typeValue.isNumber() ? typeValue.asInt() : null);
            line.setColor(node.path("color").asText(null));
            JsonNode colorValue = node.get("color_value");
            line.setColorValue(colorValue != null && colorValue.isNumber() ? colorValue.asInt() : null);
            line.setLineWidthM(num(node, "line_width_m"));

            List<MapFrameResponse.MapPoint> points = new ArrayList<>();
            JsonNode pointNodes = node.path("points");
            if (pointNodes.isArray()) {
                for (JsonNode pt : pointNodes) {
                    if (!pt.isObject()) {
                        continue;
                    }
                    MapFrameResponse.MapPoint p = new MapFrameResponse.MapPoint();
                    p.setX(num(pt, "x"));
                    p.setY(num(pt, "y"));
                    points.add(p);
                }
            }
            line.setPoints(points);
            lines.add(line);
        }
        return lines;
    }

    /**
     * 构造空地图帧（200，缓存命中但窗口/时段无数据，frame=ego）
     */
    private MapFrameResponse emptyMapFrame(long timeNs) {
        MapFrameResponse resp = new MapFrameResponse();
        resp.setTime(ReplayTimeUtil.formatNs(timeNs));
        resp.setTimeNs(timeNs);
        resp.setFrame("ego");
        resp.setUnit("m");
        resp.setLaneBoundaries(List.of());
        resp.setRoadBoundaries(List.of());
        resp.setStopLines(List.of());
        return resp;
    }

    // ========================================================================
    // 分段缓存通用读取（obstacles/perception/map 共用）
    // ========================================================================

    /**
     * 读取 ROSData 回放数据（导入流水线产物，单文件 JSON，不触发重建提取）。
     *
     * <p>区别于 obstacles/perception/map 的 read-through 缓存：该产物在导入
     * 流水线 PARSING 阶段由 extract_replay.py 一次性生成，接口只读不建。</p>
     */
    @Override
    public JsonNode getRosData(String sliceId) {
        String key = StorageKeys.replayRosData(sliceId);
        if (!storageAccess.exists(key)) {
            throw new RuntimeException(
                    "REPLAY_DATA_NOT_FOUND: ROSData replay data not generated for sliceId=" + sliceId);
        }
        try {
            JsonNode data = objectMapper.readTree(storageAccess.readString(key));
            if (!data.isObject()) {
                throw new RuntimeException(
                        "REPLAY_DATA_NOT_FOUND: ROSData replay data corrupted, key=" + key);
            }
            return data;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to read ROSData replay data: sliceId={}, key={}", sliceId, key, e);
            throw new RuntimeException(
                    "REPLAY_DATA_NOT_FOUND: ROSData replay data unreadable, key=" + key);
        }
    }

    /**
     * 解析 time（RFC3339 → ns）并归一化 window_ms（默认 200，[1, 1000]）
     *
     * @throws RuntimeException INVALID_PARAMETER（格式非法，400）
     */
    private TimeWindow parseTimeWindow(String time, Integer windowMs) {
        long timeNs;
        try {
            timeNs = ReplayTimeUtil.parseNs(time);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("INVALID_PARAMETER: time must be UTC RFC3339, got=" + time);
        }
        int window = windowMs == null ? 200 : Math.max(1, Math.min(1000, windowMs));
        return new TimeWindow(timeNs, window * 1_000_000L);
    }

    /**
     * 定位覆盖 timeNs 的分段（index.json → segments[]）
     *
     * @return segment 节点；segment 为 null 时 indexPresent 区分「已提取但无覆盖」
     *         （返回空帧 200）与「未提取过/索引损坏」（触发重建提取）
     */
    private SegmentLookup locateSegment(String sliceId, String kind, long timeNs) {
        String indexPath = StorageKeys.replayIndex(sliceId, kind);
        if (!storageAccess.exists(indexPath)) {
            return new SegmentLookup(null, false, true);
        }
        try {
            JsonNode index = objectMapper.readTree(storageAccess.readString(indexPath));
            JsonNode segments = index.isArray() ? index : index.path("segments");
            if (!segments.isArray()) {
                return new SegmentLookup(null, false, true); // 索引损坏（结构非法）→ 触发重建提取
            }
            boolean indexCalibrated = index.isArray() ? true : index.path("calibrated").asBoolean(true);
            for (JsonNode seg : segments) {
                long segStart = seg.path("segment_start_ns").asLong(Long.MIN_VALUE);
                long segEnd = seg.path("segment_end_ns").asLong(Long.MAX_VALUE);
                if (segStart <= timeNs && timeNs <= segEnd) {
                    return new SegmentLookup(seg, true, indexCalibrated);
                }
            }
            // 索引存在且可解析但无覆盖 segment：数据间隙/时间越界/空索引 → 空帧 200（不再触发提取）
            log.debug("Replay {}: index exists but no segment covers timeNs={} for slice {}, "
                    + "returning empty frame", kind, timeNs, sliceId);
            return new SegmentLookup(null, true, indexCalibrated);
        } catch (Exception e) {
            log.warn("Failed to read {} index: {}, trigger re-extraction", kind, indexPath, e);
            return new SegmentLookup(null, false, true); // 索引损坏（解析失败）→ 触发重建提取
        }
    }

    /**
     * 读分段文件 → 帧数组节点（兼容两种形态：顶层帧数组，或 { ..., frames: [...] } 对象）
     *
     * @return 帧数组；文件缺失/损坏/key 越界 → null（触发重建提取）
     */
    private JsonNode readSegmentFrames(String sliceId, String kind, JsonNode seg) {
        String file = seg.path("file").asText("");
        if (file.isBlank()) {
            return null;
        }
        try {
            // file 可为 "segments/123.json" 或 "123.json"：统一 "/" 分隔的 key 拼接，
            // 拒绝前导 "/" 与 ".."（防御路径穿越，与 index.json 由后端维护的事实一致）
            if (file.startsWith("/") || file.contains("..")) {
                log.warn("Replay {}: segment file outside cache keyspace: {}", kind, file);
                return null;
            }
            String segmentKey = StorageKeys.replayKindPrefix(sliceId, kind) + file;
            if (!storageAccess.exists(segmentKey)) {
                return null;
            }
            JsonNode frames = objectMapper.readTree(storageAccess.readString(segmentKey));
            if (frames.isObject()) {
                frames = frames.path("frames");
            }
            if (!frames.isArray()) {
                return null;
            }
            return frames;
        } catch (Exception e) {
            log.warn("Failed to read {} segment file: sliceId={}, file={}", kind, sliceId, file, e);
            return null;
        }
    }

    /**
     * 窗口内最近一帧（|stamp - timeNs| 落在 [0, windowNs] 且绝对距离最小）。
     *
     * <p>双向窗口（过去 + 未来）：前端以整秒时刻请求（onClockTick 取整），而感知/障碍
     * 帧相位（如 10Hz smooth_object 首帧在段起点 +158ms）与整秒边界不对齐，若只向过去
     * 找（stamp &lt;= timeNs）则段起点/数据起点后的首个窗口常为空帧。取绝对距离最近帧，
     * 段首帧偏移 ≤ 窗口即可命中。平局（前后帧等距）时优先过去帧，保持"不提前展示未来帧"。</p>
     *
     * @return 最近帧节点；窗口内无帧 → null
     */
    private JsonNode findBestFrameInWindow(JsonNode frames, long timeNs, long windowNs) {
        JsonNode best = null;
        long bestDist = Long.MAX_VALUE;
        boolean bestIsPast = false;
        for (JsonNode frame : frames) {
            long stamp = frameStampNs(frame);
            long dist = Math.abs(stamp - timeNs);
            if (dist > windowNs) {
                continue;
            }
            if (dist < bestDist || (dist == bestDist && !bestIsPast && stamp <= timeNs)) {
                best = frame;
                bestDist = dist;
                bestIsPast = stamp <= timeNs;
            }
        }
        return best;
    }

    /** 帧时间戳（优先 time_ns，兼容旧产物 stamp_ns），unit: ns */
    private long frameStampNs(JsonNode frame) {
        return frame.path("time_ns").asLong(frame.path("stamp_ns").asLong(Long.MIN_VALUE));
    }

    /** 请求时间窗口（解析后） */
    private record TimeWindow(long timeNs, long windowNs) {
    }

    /** 分段定位结果：segment 为 null 时 indexPresent 区分空帧 200 与触发重建提取 */
    private record SegmentLookup(JsonNode segment, boolean indexPresent, boolean indexCalibrated) {
    }

    // ========================================================================
    // Raw 消息流（分页）
    // ========================================================================

    @Override
    public RawMessagePageResponse getMessages(String sliceId, String topic, String startTs, String endTs,
                                              Integer offset, Integer limit) {
        requireSlice(sliceId);

        // 1. topic 必须存在于切片 topic 清单（404）
        SensorStream stream = sensorStreamMapper.selectOne(
                new LambdaQueryWrapper<SensorStream>()
                        .eq(SensorStream::getSliceId, sliceId)
                        .eq(SensorStream::getTopic, topic)
                        .last("LIMIT 1")
        );
        if (stream == null) {
            throw new RuntimeException("REPLAY_TOPIC_NOT_FOUND: " + topic + " not in slice " + sliceId);
        }

        // 2. 参数校验（v1.4 起不再按前缀排除 topic：
        //    /momenta_public/*、/navigation/* 实测 frame_id 内嵌 JSON 业务负载，
        //    Raw 消息接口可正常提取与查询）
        long startNs;
        long endNs;
        try {
            startNs = ReplayTimeUtil.parseNs(startTs);
            endNs = ReplayTimeUtil.parseNs(endTs);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("INVALID_PARAMETER: start_ts/end_ts must be UTC RFC3339, got="
                    + startTs + " ~ " + endTs);
        }
        if (startNs >= endNs) {
            throw new RuntimeException("INVALID_PARAMETER: start_ts must be before end_ts");
        }
        int pageOffset = offset == null ? 0 : Math.max(0, offset);
        int pageLimit = limit == null ? 50 : Math.max(1, Math.min(200, limit));

        // 4. 读 JSONL 缓存分页（无缓存返回空页，前端可触发 POST replay/extract）
        return readMessagesPage(sliceId, topic, stream.getMsgType(), startNs, endNs, pageOffset, pageLimit);
    }

    // ========================================================================
    // 视频流 / topic 清单组装
    // ========================================================================

    private List<ReplayContextResponse.ReplayVideoStream> buildVideoStreams(String sliceId) {
        List<SensorStream> cameraStreams = sensorStreamMapper.selectList(
                new LambdaQueryWrapper<SensorStream>()
                        .eq(SensorStream::getSliceId, sliceId)
                        .eq(SensorStream::getSensorCategory, "camera")
                        .orderByAsc(SensorStream::getTopic)
        );
        List<ReplayContextResponse.ReplayVideoStream> streams = new ArrayList<>();
        for (SensorStream cs : cameraStreams) {
            String position = extractCameraPosition(cs);
            if (position == null) {
                log.warn("Skip camera stream with unmappable position: sliceId={}, topic={}", sliceId, cs.getTopic());
                continue;
            }
            ReplayContextResponse.ReplayVideoStream item = new ReplayContextResponse.ReplayVideoStream();
            item.setId(position);
            String displayName = CAMERA_DISPLAY_NAMES.getOrDefault(position, position);
            item.setName(displayName);
            item.setLabel(displayName);
            item.setTopic(cs.getTopic());
            item.setMp4Available(hasMp4(cs, sliceId));
            item.setUrl(videoUrl(cs, sliceId));
            item.setDefault(DEFAULT_4_CAMERAS.contains(position));
            streams.add(item);
        }
        return streams;
    }

    /**
     * 从相机 SensorStream 提取 CameraPosition
     *
     * <p>BAG 切片 sensor_name 为 NULL，权威来源是 topic（/sensor/camera_&lt;position&gt;/video）；
     * 无法从 topic 提取时回退 sensor_name（CFDI 为 F-number 形式，不属契约枚举，仅兜底）。</p>
     */
    private String extractCameraPosition(SensorStream cs) {
        if (cs.getTopic() != null) {
            Matcher m = CAMERA_TOPIC_PATTERN.matcher(cs.getTopic());
            if (m.matches()) {
                return m.group(1);
            }
        }
        if (cs.getSensorName() != null && CAMERA_DISPLAY_NAMES.containsKey(cs.getSensorName())) {
            return cs.getSensorName();
        }
        return null;
    }

    private String mp4FileName(SensorStream cs) {
        return cs.getTopic().replace("/", "_").replace(".", "_") + ".mp4";
    }

    private boolean hasMp4(SensorStream cs, String sliceId) {
        try {
            return storageAccess.exists(StorageKeys.video(sliceId, mp4FileName(cs)));
        } catch (Exception e) {
            return false;
        }
    }

    private String videoUrl(SensorStream cs, String sliceId) {
        return "/data/videos/" + sliceId + "/" + mp4FileName(cs);
    }

    private List<ReplayContextResponse.ReplayTopicInfo> buildTopicInfos(String sliceId) {
        List<SensorStream> streams = sensorStreamMapper.selectList(
                new LambdaQueryWrapper<SensorStream>()
                        .eq(SensorStream::getSliceId, sliceId)
                        .orderByAsc(SensorStream::getTopic)
        );
        List<ReplayContextResponse.ReplayTopicInfo> infos = new ArrayList<>();
        for (SensorStream s : streams) {
            ReplayContextResponse.ReplayTopicInfo info = new ReplayContextResponse.ReplayTopicInfo();
            info.setName(s.getTopic());
            info.setType(s.getMsgType());
            info.setSensorCategory(s.getSensorCategory());
            info.setMsgCount(s.getMsgCount());
            info.setRateHz(s.getFrequencyHz());
            // v1.4 修正：momenta_public/*、navigation/* 实测 frame_id 内嵌 JSON
            // 业务负载，全部 topic 均含 payload，不再按前缀置灰
            info.setHasPayload(true);
            info.setDataCategory(ReplayTopicUtil.dataCategory(s.getTopic()));
            info.setFirstTs(format(s.getFirstTs()));
            info.setLastTs(format(s.getLastTs()));
            infos.add(info);
        }
        return infos;
    }

    private List<String> buildTagNames(String sliceId) {
        // 本地 tag 表已随 V29 移除：只查关联行，名称从 SIMS 词表缓存解析
        List<DataSliceTag> rows = dataSliceTagMapper.selectBySliceId(sliceId);
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> simsTagCatalog.nameOf(row.getTagId())).toList();
    }

    /**
     * 是否已存在障碍/消息/感知/地图提取缓存（context.data_ready）
     */
    private boolean isDataReady(String sliceId) {
        for (String kind : new String[]{"obstacles", "perception", "map"}) {
            if (storageAccess.exists(StorageKeys.replayIndex(sliceId, kind))) {
                return true;
            }
        }
        String messagesPrefix = StorageKeys.replayMessagesPrefix(sliceId);
        for (String key : storageAccess.list(messagesPrefix)) {
            if (key.endsWith("/index.json")) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // 障碍缓存读取
    // ========================================================================

    /**
     * 读障碍缓存段：命中返回帧（窗口内最近一帧）；未命中返回 null（触发提取）
     *
     * <p>S1 防无限循环（Review 修复要求）：index.json 存在且可解析 = 该切片已提取过，
     * 此时任何时刻查不到帧（数据间隙/时间越界/空索引 segments:[]）一律返回空帧 200，
     * 不再触发提取。否则循环：任务 SUCCESS 仍无帧 → 前端轮询成功重试 → 再 202 建任务。</p>
     *
     * @return 帧响应（命中或空帧）；null 仅当无缓存上下文（触发重建提取）
     */
    private ObstacleFrameResponse readObstacleFrame(String sliceId, long timeNs, long windowNs) {
        SegmentLookup lookup = locateSegment(sliceId, "obstacles", timeNs);
        if (lookup.segment() == null) {
            // index 存在且可解析但无覆盖 segment：数据间隙/时间越界/空索引 → 空帧 200（不再触发提取）
            if (lookup.indexPresent()) {
                // W1：空帧 calibrated 继承 index 的实际值，避免数据间隙误报「标定缺失」
                return emptyFrame(timeNs, lookup.indexCalibrated());
            }
            return null; // 未提取过/索引损坏 → 触发提取
        }
        JsonNode frames = readSegmentFrames(sliceId, "obstacles", lookup.segment());
        if (frames == null) {
            return null; // 分段文件缺失/损坏 → 触发重建提取
        }
        JsonNode best = findBestFrameInWindow(frames, timeNs, windowNs);
        if (best == null) {
            // 分段存在但窗口内无帧（数据间隙）→ 返回空帧（200），不算缓存未命中；
            // W1：calibrated 继承该段/index 的实际值，避免数据间隙误报「标定缺失」
            return emptyFrame(timeNs, lookup.segment().path("calibrated").asBoolean(lookup.indexCalibrated()));
        }
        return toFrameResponse(best, frameStampNs(best), lookup.indexCalibrated());
    }

    private ObstacleFrameResponse toFrameResponse(JsonNode frame, long stampNs, boolean defaultCalibrated) {
        ObstacleFrameResponse resp = new ObstacleFrameResponse();
        resp.setTime(ReplayTimeUtil.formatNs(stampNs));
        resp.setTimeNs(stampNs);
        resp.setFrame("ego");
        resp.setUnit("m");
        resp.setCalibrated(frame.path("calibrated").asBoolean(defaultCalibrated));

        List<ObstacleFrameResponse.ObstacleTarget> obstacles = new ArrayList<>();
        JsonNode obstacleNodes = frame.path("obstacles");
        if (obstacleNodes.isArray()) {
            for (JsonNode node : obstacleNodes) {
                if (obstacles.size() >= MAX_TARGETS_PER_FRAME) {
                    break;
                }
                if (!node.isObject()) {
                    continue;
                }
                obstacles.add(toObstacleTarget(node));
            }
        }
        resp.setObstacles(obstacles);
        return resp;
    }

    /**
     * 帧内障碍目标 → 契约 ObstacleTarget（容忍缺失字段，未知字段丢弃）
     */
    private ObstacleFrameResponse.ObstacleTarget toObstacleTarget(JsonNode node) {
        ObstacleFrameResponse.ObstacleTarget t = new ObstacleFrameResponse.ObstacleTarget();
        t.setSensor(node.path("sensor").asText(null));
        t.setType(node.path("type").asText(null));
        t.setId(node.path("id").asText(""));
        t.setX(num(node, "x"));
        t.setY(num(node, "y"));
        t.setZ(num(node, "z"));
        t.setVx(num(node, "vx"));
        t.setVy(num(node, "vy"));
        t.setRcs(num(node, "rcs"));
        t.setLengthM(num(node, "length_m"));
        t.setWidthM(num(node, "width_m"));
        t.setStartX(num(node, "start_x"));
        t.setStartY(num(node, "start_y"));
        t.setEndX(num(node, "end_x"));
        t.setEndY(num(node, "end_y"));
        t.setPointX(num(node, "point_x"));
        t.setPointY(num(node, "point_y"));
        t.setRangeM(num(node, "range_m"));
        t.setHeightType(node.path("height_type").asText(null));
        t.setProbability(num(node, "probability"));
        t.setStatus(node.path("status").asText("valid"));
        return t;
    }

    private Double num(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    /**
     * 构造空帧（200，缓存命中但窗口/时段无数据）
     *
     * @param timeNs     请求时刻（作为空帧时间戳）
     * @param calibrated W1：继承 index/segment 的实际标定状态，避免数据间隙误报「标定缺失」；
     *                   无缓存上下文（如切片无雷达/超声波 topic）时传 false
     */
    private ObstacleFrameResponse emptyFrame(long timeNs, boolean calibrated) {
        ObstacleFrameResponse resp = new ObstacleFrameResponse();
        resp.setTime(ReplayTimeUtil.formatNs(timeNs));
        resp.setTimeNs(timeNs);
        resp.setFrame("ego");
        resp.setUnit("m");
        resp.setCalibrated(calibrated);
        resp.setObstacles(List.of());
        return resp;
    }

    private List<String> obstacleTopics(String sliceId) {
        return topicNamesByFilter(sliceId, s -> s.getTopic() != null
                && ("radar".equals(s.getSensorCategory()) || "ultrasonic".equals(s.getSensorCategory())));
    }

    @Override
    public List<String> perceptionTopics(String sliceId) {
        return topicNamesByFilter(sliceId, s -> ReplayTopicUtil.isPerceptionTopic(s.getTopic()));
    }

    @Override
    public List<String> mapTopics(String sliceId) {
        return topicNamesByFilter(sliceId, s -> ReplayTopicUtil.isMapTopic(s.getTopic()));
    }

    /**
     * 按条件筛选切片 topic 名（升序）
     */
    private List<String> topicNamesByFilter(String sliceId, java.util.function.Predicate<SensorStream> filter) {
        List<SensorStream> streams = sensorStreamMapper.selectList(
                new LambdaQueryWrapper<SensorStream>()
                        .eq(SensorStream::getSliceId, sliceId)
                        .orderByAsc(SensorStream::getTopic)
        );
        List<String> topics = new ArrayList<>();
        for (SensorStream s : streams) {
            if (filter.test(s)) {
                topics.add(s.getTopic());
            }
        }
        return topics;
    }

    // ========================================================================
    // 消息缓存读取（分页）
    // ========================================================================

    private RawMessagePageResponse readMessagesPage(String sliceId, String topic, String msgType,
                                                    long startNs, long endNs, int offset, int limit) {
        RawMessagePageResponse page = new RawMessagePageResponse();
        page.setTopic(topic);
        page.setHasPayload(true);

        String topicSafe = topic.replace("/", "_").replace(".", "_");
        String indexPath = StorageKeys.replayMessagesIndex(sliceId, topicSafe);
        if (!storageAccess.exists(indexPath)) {
            // 未提取过：返回空页（total=0），前端据此触发 POST replay/extract
            page.setType(msgType != null ? msgType : "unknown");
            page.setTotal(0L);
            page.setMessages(List.of());
            page.setNextOffset(null);
            return page;
        }
        try {
            JsonNode index = objectMapper.readTree(storageAccess.readString(indexPath));
            page.setType(index.path("type").asText(msgType != null ? msgType : "unknown"));

            String topicDirKey = StorageKeys.replayMessagesPrefix(sliceId) + topicSafe;
            List<FileEntry> files = parseFileEntries(index, topicDirKey);
            List<FileEntry> overlap = new ArrayList<>();
            for (FileEntry f : files) {
                if (f.endNs >= startNs && f.startNs <= endNs) {
                    overlap.add(f);
                }
            }

            // Pass 1: 区间内消息总数（整文件用 index count，首尾部分文件扫行数）
            long total = 0;
            for (FileEntry f : overlap) {
                if (f.startNs >= startNs && f.endNs <= endNs) {
                    total += f.count;
                } else {
                    total += countLinesInRange(f, startNs, endNs);
                }
            }
            page.setTotal(total);

            // Pass 2: 分页收集（seq 从 1 起，与 offset 对应）
            List<RawMessagePageResponse.RawMessageItem> items = new ArrayList<>();
            long cursor = 0; // 已推进的区间内消息数
            for (FileEntry f : overlap) {
                if (items.size() >= limit) {
                    break;
                }
                if (f.startNs >= startNs && f.endNs <= endNs) {
                    // 整文件在区间内：index count 可直接跳过；需要取数时才读文件
                    if (cursor + f.count > offset) {
                        long skipInFile = Math.max(0, offset - cursor);
                        // limit 为目标总条数：跨文件累计收集（非"还需条数"，否则后续文件少取）
                        collectFromFile(f, skipInFile, limit, items, offset);
                    }
                    cursor += f.count;
                } else {
                    // 部分文件：逐行过滤
                    collectFromFilePartial(f, startNs, endNs, offset, limit, items, cursor);
                    cursor += countLinesInRange(f, startNs, endNs);
                }
            }
            page.setMessages(items);
            page.setNextOffset((offset + items.size()) < total ? offset + items.size() : null);
            return page;
        } catch (Exception e) {
            log.warn("Failed to read message cache page: sliceId={}, topic={}", sliceId, topic, e);
            page.setTotal(0L);
            page.setMessages(List.of());
            page.setNextOffset(null);
            return page;
        }
    }

    /**
     * 从整文件（skipInFile 行起）收集条目；seq = 全局区间位置 + 1
     *
     * @param limit 本页目标总条数（跨文件累计，items.size() 达到 limit 即停止）
     */
    private void collectFromFile(FileEntry f, long skipInFile, int limit,
                                 List<RawMessagePageResponse.RawMessageItem> items, int offset) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(storageAccess.open(f.key()), StandardCharsets.UTF_8))) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null && items.size() < limit) {
                if (line.isBlank()) {
                    continue;
                }
                if (lineNo++ < skipInFile) {
                    continue;
                }
                RawMessagePageResponse.RawMessageItem item = toMessageItem(line, offset + items.size() + 1);
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read message file: {}", f.key(), e);
        }
    }

    /**
     * 部分文件：逐行过滤时间区间后收集
     */
    private void collectFromFilePartial(FileEntry f, long startNs, long endNs, int offset, int limit,
                                        List<RawMessagePageResponse.RawMessageItem> items, long cursor) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(storageAccess.open(f.key()), StandardCharsets.UTF_8))) {
            String line;
            long position = cursor; // 区间内位置（与外部 cursor 同步推进）
            while ((line = reader.readLine()) != null && items.size() < limit) {
                if (line.isBlank()) {
                    continue;
                }
                long stampNs = extractStampNs(line);
                if (stampNs < startNs || stampNs > endNs) {
                    continue;
                }
                if (position >= offset) {
                    RawMessagePageResponse.RawMessageItem item = toMessageItem(line, (int) position + 1);
                    if (item != null) {
                        items.add(item);
                    }
                }
                position++;
            }
        } catch (Exception e) {
            log.warn("Failed to read message file (partial): {}", f.key(), e);
        }
    }

    /**
     * 部分文件在区间内的消息数（整文件可直接用 index count 时不要调用）
     */
    private long countLinesInRange(FileEntry f, long startNs, long endNs) {
        long count = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(storageAccess.open(f.key()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                long stampNs = extractStampNs(line);
                if (stampNs >= startNs && stampNs <= endNs) {
                    count++;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to count message file: {}", f.key(), e);
        }
        return count;
    }

    private long extractStampNs(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            return node.path("stamp_ns").asLong(Long.MIN_VALUE);
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private RawMessagePageResponse.RawMessageItem toMessageItem(String line, int seq) {
        try {
            JsonNode node = objectMapper.readTree(line);
            RawMessagePageResponse.RawMessageItem item = new RawMessagePageResponse.RawMessageItem();
            item.setSeq(seq);
            long stampNs = node.path("stamp_ns").asLong(0);
            item.setStampNs(stampNs);
            item.setStamp(ReplayTimeUtil.formatNs(stampNs));
            item.setData(node.path("data"));
            if (node.hasNonNull("header_stamp_ns")) {
                item.setHeaderStampNs(node.path("header_stamp_ns").asLong());
            }
            if (node.hasNonNull("sensor_timestamp_us")) {
                item.setSensorTimestampUs(node.path("sensor_timestamp_us").asLong());
            }
            return item;
        } catch (Exception e) {
            log.warn("Failed to parse message line: {}", line, e);
            return null;
        }
    }

    /**
     * 消息缓存索引文件条目（start/end 缺失时用邻居推断，容错）
     *
     * <p>key 为存储引用：topic 目录前缀 + 文件名，统一 "/" 分隔；
     * 拒绝前导 "/" 与 ".."（防御路径穿越，index.json 虽由后端维护，纵深防御保持一致）。</p>
     */
    private List<FileEntry> parseFileEntries(JsonNode index, String topicDirKey) {
        List<FileEntry> files = new ArrayList<>();
        JsonNode filesNode = index.path("files");
        if (!filesNode.isArray()) {
            return files;
        }
        long indexEndNs = index.path("end_ns").asLong(Long.MAX_VALUE);
        long prevEnd = index.path("start_ns").asLong(Long.MIN_VALUE);
        for (JsonNode f : filesNode) {
            long startNs = f.path("start_ns").asLong(Long.MIN_VALUE);
            long endNs = f.hasNonNull("end_ns") ? f.path("end_ns").asLong() : prevEnd;
            if (endNs == Long.MIN_VALUE) {
                endNs = indexEndNs;
            }
            String file = f.path("file").asText("");
            long count = f.path("count").asLong(0);
            if (file.isBlank()) {
                continue;
            }
            if (file.startsWith("/") || file.contains("..")) {
                log.warn("Replay messages: file entry outside topic keyspace, skipped: {}", file);
                continue;
            }
            String fileKey = topicDirKey + "/" + file;
            if (!storageAccess.exists(fileKey)) {
                continue;
            }
            files.add(new FileEntry(startNs, endNs, fileKey, count));
            prevEnd = endNs;
        }
        files.sort(Comparator.comparingLong(FileEntry::startNs));
        return files;
    }

    /** 消息文件条目（key 为存储引用） */
    private record FileEntry(long startNs, long endNs, String key, long count) {
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private DataSlice requireSlice(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        // 归档拦截：minio 模式下归档切片（storage_type=LOCAL）数据读取 → 410 DATA_ARCHIVED
        storageAccess.assertNotArchived(slice);
        return slice;
    }

    private String format(java.time.LocalDateTime ldt) {
        return ReplayTimeUtil.formatLocalDateTime(ldt);
    }

    private VehicleSpec defaultVehicle() {
        VehicleSpec spec = new VehicleSpec();
        spec.setLengthM(4.8);
        spec.setWidthM(1.9);
        spec.setWheelbaseM(2.8);
        return spec;
    }
}
