package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.TrajectoryQueryResult;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.security.RequireTrajectoryWrite;
import com.isim.dims.service.TrajectoryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * GPS 轨迹 Controller
 *
 * 负责 GPS 轨迹数据的查询和异步提取管理。
 * 对应 OpenAPI paths:
 *   GET  /v1/data-slices/{sliceId}/trajectory
 *   POST /v1/data-slices/{sliceId}/trajectory/extract
 *   GET  /v1/trajectory-tasks/{taskId}
 *
 * GET trajectory 三态响应：
 *   200 + TrajectoryResponse                  — 有轨迹数据（gps_trajectory 缓存命中）
 *   202 + { status, task_id, slice_id }       — 有 GPS sensor_stream 但轨迹未提取，已自动触发异步提取
 *   404 + TRAJECTORY_NO_GPS_DATA              — 无 GPS sensor_stream，该切片确认为空
 *   409 + TRAJECTORY_IMPORT_NOT_READY         — 导入流水线未完成，暂无法判断
 *   409 + TRAJECTORY_BAG_FILE_MISSING         — 有 GPS 流但 bag 文件缺失
 * @feature: trajectory
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class TrajectoryController {

    private final TrajectoryService trajectoryService;

    /**
     * 查询 GPS 轨迹
     *
     * GET /v1/data-slices/{sliceId}/trajectory
     * 三级判定：gps_trajectory 缓存 → sensor_stream GPS 能力 → 自动触发异步提取。
     *
     * @param sliceId 切片 UUID
     * @return 200 + 轨迹数据 | 202 + 提取任务 | 404 无 GPS | 409 未就绪/文件缺失
     */
    @GetMapping("/data-slices/{sliceId}/trajectory")
    public ResponseEntity<?> getTrajectory(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getTrajectory request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            TrajectoryQueryResult result = trajectoryService.getTrajectory(sliceId);
            switch (result.getStatus()) {
                case "READY":
                    return ResponseEntity.ok(result.getTrajectory());
                case "NOT_EXTRACTED": {
                    Map<String, Object> body = new HashMap<>();
                    body.put("status", "TRAJECTORY_NOT_EXTRACTED");
                    body.put("task_id", result.getTaskId());
                    body.put("slice_id", sliceId);
                    body.put("message", result.getMessage());
                    log.info("Trajectory not extracted, auto-triggered: sliceId={}, taskId={}",
                            sliceId, result.getTaskId());
                    return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
                }
                case "NO_GPS_DATA":
                    log.info("Slice has no GPS sensor stream: sliceId={}", sliceId);
                    return ResponseEntity.status(404)
                            .body(ErrorResponse.of("TRAJECTORY_NO_GPS_DATA", result.getMessage(), traceId));
                case "IMPORT_NOT_READY":
                    log.warn("Import pipeline not ready, cannot judge GPS: sliceId={}", sliceId);
                    return ResponseEntity.status(409)
                            .body(ErrorResponse.of("TRAJECTORY_IMPORT_NOT_READY", result.getMessage(), traceId));
                case "BAG_FILE_MISSING":
                    log.warn("GPS stream present but bag file missing: sliceId={}", sliceId);
                    return ResponseEntity.status(409)
                            .body(ErrorResponse.of("TRAJECTORY_BAG_FILE_MISSING", result.getMessage(), traceId));
                default:
                    log.error("Unknown trajectory query status: sliceId={}, status={}", sliceId, result.getStatus());
                    return ResponseEntity.status(500)
                            .body(ErrorResponse.of("TRAJECTORY_QUERY_FAILED",
                                    "Unknown status: " + result.getStatus(), traceId));
            }
        } catch (StorageArchivedException e) {
            // 归档切片（storage_type=LOCAL + minio 模式）：bag 数据内容访问 → 410
            log.warn("getTrajectory rejected (archived slice): sliceId={}, message={}", sliceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("SLICE_NOT_FOUND")) {
                log.warn("Slice not found: sliceId={}", sliceId);
                return ResponseEntity.status(404)
                        .body(ErrorResponse.of("SLICE_NOT_FOUND", msg, traceId));
            }
            log.error("getTrajectory failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("TRAJECTORY_QUERY_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 提取 GPS 轨迹（异步）
     *
     * POST /v1/data-slices/{sliceId}/trajectory/extract
     * 强制触发 GPS 轨迹提取，由前端在检测到无缓存时自动调用。
     *
     * @param sliceId 切片 UUID
     * @param request 提取请求（可选参数，为空则自动检测 GNSS topic）
     * @return 202 + { task_id, status }
     */
    @RequireTrajectoryWrite
    @PostMapping("/data-slices/{sliceId}/trajectory/extract")
    public ResponseEntity<?> extractTrajectory(
            @PathVariable("sliceId") String sliceId,
            @RequestBody(required = false) ExtractRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("extractTrajectory request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            ExtractRequest req = request != null ? request : new ExtractRequest();
            Map<String, String> response = trajectoryService.extractTrajectory(sliceId, req);
            return ResponseEntity.status(202).body(response);
        } catch (StorageArchivedException e) {
            // 归档切片（storage_type=LOCAL + minio 模式）：bag 数据内容访问 → 410
            log.warn("extractTrajectory rejected (archived slice): sliceId={}, message={}", sliceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("extractTrajectory failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("TRAJECTORY_EXTRACT_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 查询轨迹提取任务进度
     *
     * GET /v1/trajectory-tasks/{taskId}
     *
     * @param taskId 任务 UUID
     * @return 200 + 任务详情（含 status, progress, error_msg）
     */
    @GetMapping("/trajectory-tasks/{taskId}")
    public ResponseEntity<?> getTrajectoryTask(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getTrajectoryTask request: taskId={}, traceId={}", taskId, traceId);
        try {
            Map<String, Object> response = trajectoryService.getTrajectoryTask(taskId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getTrajectoryTask failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("TRAJECTORY_TASK_FAILED", e.getMessage(), traceId));
        }
    }
}
