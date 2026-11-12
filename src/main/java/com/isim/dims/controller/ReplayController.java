package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.MapFrameResponse;
import com.isim.dims.dto.ObstacleFrameResponse;
import com.isim.dims.dto.ObstacleQueryResult;
import com.isim.dims.dto.PerceptionFrameResponse;
import com.isim.dims.dto.RawMessagePageResponse;
import com.isim.dims.dto.ReplayContextResponse;
import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.dto.ReplayExtractionResponse;
import com.isim.dims.dto.ReplayFrameQueryResult;
import com.isim.dims.security.RequireReplayWrite;
import com.isim.dims.service.ReplayExtractionTaskService;
import com.isim.dims.service.ReplayService;
import com.isim.dims.util.TraceIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 2D 数据回放 Controller
 *
 * 对应 OpenAPI v1.3.0 paths（契约为唯一实现标准）:
 *   GET  /v1/data-slices/{sliceId}/replay/context
 *   GET  /v1/data-slices/{sliceId}/replay/obstacles
 *   GET  /v1/data-slices/{sliceId}/replay/perception   （P1.5 新增，v1.4 感知产物读取）
 *   GET  /v1/data-slices/{sliceId}/replay/map          （P1.5 新增，v1.4 地图产物读取）
 *   GET  /v1/data-slices/{sliceId}/replay/messages
 *   POST /v1/data-slices/{sliceId}/replay/extract
 * @feature: replay
 */
@Slf4j
@RestController
@RequestMapping("/v1/data-slices/{sliceId}/replay")
@RequiredArgsConstructor
public class ReplayController {

    private final ReplayService replayService;
    private final ReplayExtractionTaskService replayExtractionTaskService;

    /**
     * 获取 2D 回放页初始化上下文
     *
     * GET /v1/data-slices/{sliceId}/replay/context
     *
     * @return 200 + ReplayContextResponse
     */
    @GetMapping("/context")
    public ResponseEntity<?> getContext(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getReplayContext request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            ReplayContextResponse response = replayService.getContext(sliceId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getReplayContext failed: {}", e.getMessage(), e);
            return toError(e, "REPLAY_CONTEXT_FAILED", traceId);
        }
    }

    /**
     * 按时间窗口获取障碍物帧（read-through 缓存）
     *
     * GET /v1/data-slices/{sliceId}/replay/obstacles?time=&window_ms=&sensor_types=
     *
     * 缓存命中 → 200 + ObstacleFrameResponse；
     * 缓存未命中 → 202 + { task_id, status }（同参进行中去重返回同一 task_id）；
     * 同切片其他参数任务进行中 → 409 REPLAY_EXTRACT_IN_PROGRESS（按 slice 串行）。
     *
     * @param time        目标时刻，UTC RFC3339（bag 时间戳，可含小数秒），必填
     * @param windowMs    时间窗口（毫秒），默认 200，[1, 1000]
     * @param sensorTypes 逗号分隔的传感器类型，默认 radar,ultrasonic
     * @return 200 / 202
     */
    @GetMapping("/obstacles")
    public ResponseEntity<?> getObstacles(
            @PathVariable("sliceId") String sliceId,
            @RequestParam("time") String time,
            @RequestParam(value = "window_ms", required = false) Integer windowMs,
            @RequestParam(value = "sensor_types", required = false) String sensorTypes,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getReplayObstacles request: sliceId={}, time={}, windowMs={}, sensorTypes={}, traceId={}",
                sliceId, time, windowMs, sensorTypes, traceId);
        try {
            ObstacleQueryResult result = replayService.getObstacles(sliceId, time, windowMs, sensorTypes);
            if (result.getTaskResponse() != null) {
                // 缓存未命中：提取任务已创建/复用（status 透传任务真实状态）
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.getTaskResponse());
            }
            ObstacleFrameResponse frame = result.getFrame();
            return ResponseEntity.ok(frame != null ? frame : new ObstacleFrameResponse());
        } catch (Exception e) {
            log.error("getReplayObstacles failed: {}", e.getMessage(), e);
            return toError(e, "REPLAY_OBSTACLES_FAILED", traceId);
        }
    }

    /**
     * 按时间窗口获取融合感知帧（read-through 缓存）
     *
     * GET /v1/data-slices/{sliceId}/replay/perception?time=&window_ms=&sensor_types=
     *
     * 缓存命中 → 200 + PerceptionFrameResponse（frame=global，WGS84 投影局部网格，
     * 非 ego 系，不得与雷达/超声波或 ddld 地图直接叠加）；
     * 缓存未命中 → 202 + { task_id, status }（同参进行中去重返回同一 task_id）；
     * 同切片其他参数任务进行中 → 409 REPLAY_EXTRACT_IN_PROGRESS（按 slice 串行）。
     *
     * @param time        目标时刻，UTC RFC3339（bag 时间戳，可含小数秒），必填
     * @param windowMs    时间窗口（毫秒），默认 200，[1, 1000]
     * @param sensorTypes 保留兼容参数（感知无传感器类型概念，忽略）
     * @return 200 / 202
     */
    @GetMapping("/perception")
    public ResponseEntity<?> getPerception(
            @PathVariable("sliceId") String sliceId,
            @RequestParam("time") String time,
            @RequestParam(value = "window_ms", required = false) Integer windowMs,
            @RequestParam(value = "sensor_types", required = false) String sensorTypes,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getReplayPerception request: sliceId={}, time={}, windowMs={}, sensorTypes={}, traceId={}",
                sliceId, time, windowMs, sensorTypes, traceId);
        try {
            ReplayFrameQueryResult<PerceptionFrameResponse> result =
                    replayService.getPerception(sliceId, time, windowMs);
            if (result.getTaskResponse() != null) {
                // 缓存未命中：提取任务已创建/复用（status 透传任务真实状态）
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.getTaskResponse());
            }
            PerceptionFrameResponse frame = result.getFrame();
            return ResponseEntity.ok(frame != null ? frame : new PerceptionFrameResponse());
        } catch (Exception e) {
            log.error("getReplayPerception failed: {}", e.getMessage(), e);
            return toError(e, "REPLAY_PERCEPTION_FAILED", traceId);
        }
    }

    /**
     * 按时间窗口获取 ddld 地图帧（read-through 缓存）
     *
     * GET /v1/data-slices/{sliceId}/replay/map?time=&window_ms=
     *
     * 缓存命中 → 200 + MapFrameResponse（frame=ego，ddld 局部系，x 前 y 左 z 上，
     * 与 smooth_object 全局系坐标系不同，不得直接叠加）；
     * 缓存未命中 → 202 + { task_id, status }（同参进行中去重返回同一 task_id）；
     * 同切片其他参数任务进行中 → 409 REPLAY_EXTRACT_IN_PROGRESS（按 slice 串行）。
     *
     * @param time     目标时刻，UTC RFC3339（bag 时间戳，可含小数秒），必填
     * @param windowMs 时间窗口（毫秒），默认 200，[1, 1000]
     * @return 200 / 202
     */
    @GetMapping("/map")
    public ResponseEntity<?> getMap(
            @PathVariable("sliceId") String sliceId,
            @RequestParam("time") String time,
            @RequestParam(value = "window_ms", required = false) Integer windowMs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getReplayMap request: sliceId={}, time={}, windowMs={}, traceId={}",
                sliceId, time, windowMs, traceId);
        try {
            ReplayFrameQueryResult<MapFrameResponse> result = replayService.getMap(sliceId, time, windowMs);
            if (result.getTaskResponse() != null) {
                // 缓存未命中：提取任务已创建/复用（status 透传任务真实状态）
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.getTaskResponse());
            }
            MapFrameResponse frame = result.getFrame();
            return ResponseEntity.ok(frame != null ? frame : new MapFrameResponse());
        } catch (Exception e) {
            log.error("getReplayMap failed: {}", e.getMessage(), e);
            return toError(e, "REPLAY_MAP_FAILED", traceId);
        }
    }

    /**
     * 查询 Raw 消息流（分页）
     *
     * GET /v1/data-slices/{sliceId}/replay/messages?topic=&start_ts=&end_ts=&offset=&limit=
     *
     * topic 不在切片清单 → 404 REPLAY_TOPIC_NOT_FOUND。
     * （v1.4 修正：/momenta_public/*、/navigation/* 实测 frame_id 内嵌 JSON 业务负载，
     * 不再排除。）
     *
     * @param topic  ROS Topic 完整路径，必填
     * @param startTs 时间区间起点（含），UTC RFC3339，必填
     * @param endTs   时间区间终点（含），UTC RFC3339，必填
     * @param offset  分页偏移，默认 0
     * @param limit   单页条数，默认 50，[1, 200]
     * @return 200 + RawMessagePageResponse
     */
    @GetMapping("/messages")
    public ResponseEntity<?> getMessages(
            @PathVariable("sliceId") String sliceId,
            @RequestParam("topic") String topic,
            @RequestParam("start_ts") String startTs,
            @RequestParam("end_ts") String endTs,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getReplayMessages request: sliceId={}, topic={}, startTs={}, endTs={}, offset={}, limit={}, traceId={}",
                sliceId, topic, startTs, endTs, offset, limit, traceId);
        try {
            RawMessagePageResponse response =
                    replayService.getMessages(sliceId, topic, startTs, endTs, offset, limit);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getReplayMessages failed: {}", e.getMessage(), e);
            return toError(e, "REPLAY_MESSAGES_FAILED", traceId);
        }
    }

    /**
     * 创建提取任务（异步）：障碍物/Raw 消息/融合感知/ddld 地图
     *
     * POST /v1/data-slices/{sliceId}/replay/extract
     * body: { type, topics[], start_ts, end_ts }
     *
     * type：obstacles（雷达/超声波障碍，默认全部雷达/超声波 topic）|
     * messages（Raw 消息，topics 必填）|
     * perception（/momenta_public/perception/* 融合感知，默认 smooth_object）|
     * map（/momenta_public/ddld/* ddld 地图，默认 landmark_vis）
     *
     * 同 (slice_id, type, topics, start_ts, end_ts) 进行中任务去重返回同一 task_id；
     * 同切片同时只允许 1 个提取任务（按 slice 串行）→ 409 REPLAY_EXTRACT_IN_PROGRESS。
     *
     * @return 202 + { task_id, status }
     */
    @RequireReplayWrite
    @PostMapping("/extract")
    public ResponseEntity<?> createExtraction(
            @PathVariable("sliceId") String sliceId,
            @RequestBody ReplayExtractionRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("createReplayExtraction request: sliceId={}, type={}, topics={}, traceId={}",
                sliceId, request != null ? request.getType() : null,
                request != null ? request.getTopics() : null, traceId);
        try {
            ReplayExtractionResponse response = replayExtractionTaskService.createTask(sliceId, request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (Exception e) {
            log.error("createReplayExtraction failed: {}", e.getMessage(), e);
            return toError(e, "REPLAY_EXTRACT_FAILED", traceId);
        }
    }

    // ========================================================================
    // ROSData 回放数据（导入流水线 extract_replay.py 产物，只读不建）
    // ========================================================================

    /**
     * 获取 ROSData 回放数据（api.md ROSData 顶层 JSON）
     * <p>
     * GET /v1/data-slices/{sliceId}/replay/rosdata
     *
     * <p>产物由导入流水线 PARSING 阶段的 extract_replay.py 一次性生成
     * （replay/{sliceId}/replay/rosdata.json），本接口只读：产物缺失
     * （旧切片/提取软失败）返回 404 REPLAY_DATA_NOT_FOUND，不触发重建提取。</p>
     *
     * @return 200 + ROSData 顶层 JSON
     */
    @GetMapping("/rosdata")
    public ResponseEntity<?> getRosData(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getReplayRosData request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            return ResponseEntity.ok(replayService.getRosData(sliceId));
        } catch (Exception e) {
            log.error("getReplayRosData failed: {}", e.getMessage(), e);
            return toError(e, "REPLAY_DATA_FAILED", traceId);
        }
    }

    // ========================================================================
    // 错误映射（统一错误结构，宪法第 7 节）
    // ========================================================================

    private ResponseEntity<ErrorResponse> toError(Exception e, String fallbackCode, String traceId) {
        String msg = e.getMessage();
        if (msg != null) {
            // 归档切片（storage_type=LOCAL + minio 模式）：数据内容读取 → 410（契约裁决 2）
            if (msg.startsWith("DATA_ARCHIVED:")) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(ErrorResponse.of("DATA_ARCHIVED", msg, traceId));
            }
            if (msg.startsWith("SLICE_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("SLICE_NOT_FOUND", msg, traceId));
            }
            if (msg.startsWith("REPLAY_TOPIC_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("REPLAY_TOPIC_NOT_FOUND", msg, traceId));
            }
            if (msg.startsWith("REPLAY_TOPIC_NO_PAYLOAD:")) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(ErrorResponse.of("REPLAY_TOPIC_NO_PAYLOAD", msg, traceId));
            }
            if (msg.startsWith("REPLAY_EXTRACT_IN_PROGRESS:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ErrorResponse.of("REPLAY_EXTRACT_IN_PROGRESS", msg, traceId));
            }
            // W2（契约 v1.3.1）：重试超限 = 永久失败，409（区别于 IN_PROGRESS 的"可轮询等待"）
            if (msg.startsWith("REPLAY_EXTRACT_RETRY_EXCEEDED:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ErrorResponse.of("REPLAY_EXTRACT_RETRY_EXCEEDED", msg, traceId));
            }
            if (msg.startsWith("REPLAY_DATA_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("REPLAY_DATA_NOT_FOUND", msg, traceId));
            }
            if (msg.startsWith("INVALID_PARAMETER:")) {
                return ResponseEntity.badRequest()
                        .body(ErrorResponse.of("INVALID_PARAMETER", msg, traceId));
            }
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(fallbackCode, msg != null ? msg : e.toString(), traceId));
    }
}
