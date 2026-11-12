package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.SignalDataResponse;
import com.isim.dims.dto.VideoStreamResponse;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.security.RequireVisualizationWrite;
import com.isim.dims.service.VisualizationPipelineService;
import com.isim.dims.service.VisualizationService;
import com.isim.dims.service.VideoImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据可视化 Controller
 *
 * 负责视频流信息查询、视频段提取、信号数据提取及结果查询。
 * 对应 OpenAPI paths:
 *   GET  /v1/data-slices/{sliceId}/video-streams
 *   POST /v1/data-slices/{sliceId}/video-streams/extract
 *   POST /v1/data-slices/{sliceId}/signal-data
 *   GET  /v1/signal-extractions/{taskId}
 * @feature: visualization
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class VisualizationController {

    private final VisualizationService visualizationService;
    private final VisualizationPipelineService visualizationPipelineService;
    private final VideoImageService videoImageService;

    /**
     * 查询切片视频流信息
     *
     * GET /v1/data-slices/{sliceId}/video-streams?event_name=xxx
     * 返回切片中 camera 类型传感器的流信息及缓存状态。
     * 传入 event_name（触发类型，即 meta.event_name）时，仅返回该事件配置的摄像头路
     * （camera_event_config.json）；未传或未知事件返回全部。点云默认不转换，不在返回范围。
     *
     * @param sliceId   切片 UUID
     * @param eventName 触发类型（可选）
     * @return 200 + 视频流信息列表
     */
    @GetMapping("/data-slices/{sliceId}/video-streams")
    public ResponseEntity<?> getVideoStreams(
            @PathVariable("sliceId") String sliceId,
            @RequestParam(value = "event_name", required = false) String eventName,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getVideoStreams request: sliceId={}, eventName={}, traceId={}", sliceId, eventName, traceId);
        try {
            VideoStreamResponse response = visualizationService.getVideoStreams(sliceId, eventName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getVideoStreams failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("VIDEO_STREAMS_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 提取视频段（异步）
     *
     * POST /v1/data-slices/{sliceId}/video-streams/extract
     * 手动触发视频段提取（当无缓存时由前端自动调用）。
     *
     * @param sliceId 切片 UUID
     * @param request 提取请求（topics、时间窗口）
     * @return 202 + { task_id, status }
     */
    @RequireVisualizationWrite
    @PostMapping("/data-slices/{sliceId}/video-streams/extract")
    public ResponseEntity<?> extractVideoSegment(
            @PathVariable("sliceId") String sliceId,
            @RequestBody ExtractRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("extractVideoSegment request: sliceId={}, topics={}, traceId={}",
                sliceId, request.getTopics(), traceId);
        try {
            Map<String, String> response = visualizationService.extractVideoSegment(sliceId, request);
            return ResponseEntity.status(202).body(response);
        } catch (StorageArchivedException e) {
            // 归档切片（storage_type=LOCAL + minio 模式）：数据内容写入 → 立即 410，不落任务
            log.warn("extractVideoSegment rejected (archived slice): sliceId={}, message={}", sliceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("extractVideoSegment failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("VIDEO_EXTRACT_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 提取信号时间序列数据（异步）
     *
     * POST /v1/data-slices/{sliceId}/signal-data
     * 按 Topic 和时间窗口提取车辆信号时序数据。
     *
     * @param sliceId 切片 UUID
     * @param request 提取请求（topics、时间窗口、maxPointsPerSignal）
     * @return 202 + { task_id, status }
     */
    @RequireVisualizationWrite
    @PostMapping("/data-slices/{sliceId}/signal-data")
    public ResponseEntity<?> extractSignalData(
            @PathVariable("sliceId") String sliceId,
            @RequestBody ExtractRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("extractSignalData request: sliceId={}, topics={}, traceId={}",
                sliceId, request.getTopics(), traceId);
        try {
            Map<String, String> response = visualizationService.extractSignalData(sliceId, request);
            return ResponseEntity.status(202).body(response);
        } catch (StorageArchivedException e) {
            // 归档切片（storage_type=LOCAL + minio 模式）：数据内容写入 → 立即 410，不落任务
            log.warn("extractSignalData rejected (archived slice): sliceId={}, message={}", sliceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("extractSignalData failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SIGNAL_EXTRACT_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 查询信号提取任务结果
     *
     * GET /v1/signal-extractions/{taskId}
     * 当 status=READY 时返回信号时间序列数据。
     *
     * @param taskId 任务 UUID
     * @return 200 + 信号提取结果
     */
    @GetMapping("/signal-extractions/{taskId}")
    public ResponseEntity<?> getSignalExtraction(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getSignalExtraction request: taskId={}, traceId={}", taskId, traceId);
        try {
            SignalDataResponse response = visualizationService.getSignalExtraction(taskId);
            return ResponseEntity.ok(response);
        } catch (StorageArchivedException e) {
            // 归档切片（storage_type=LOCAL + minio 模式）：数据内容读取 → 410
            log.warn("getSignalExtraction rejected (archived slice): taskId={}, message={}", taskId, e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("getSignalExtraction failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SIGNAL_EXTRACTION_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 查询视频提取任务结果
     *
     * GET /v1/video-extractions/{taskId}
     * 轮询视频提取任务状态，直至 READY 或 ERROR。
     *
     * @param taskId 任务 UUID
     * @return 200 + 任务状态快照，未找到时返回 404
     */
    @GetMapping("/video-extractions/{taskId}")
    public ResponseEntity<?> getVideoExtraction(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getVideoExtraction request: taskId={}, traceId={}", taskId, traceId);
        Map<String, Object> snapshot = visualizationPipelineService.getVideoExtractionTask(taskId);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of("TASK_NOT_FOUND", "视频提取任务未找到: " + taskId, traceId));
        }
        return ResponseEntity.ok(snapshot);
    }

    /**
     * 查询切片每 5 秒保存的帧图列表
     *
     * GET /v1/data-slices/{sliceId}/video-images
     * 返回视频提取流水线产出的所有 5 秒帧图（含 url，可直接用于 <img> 展示与语义检索）。
     *
     * @param sliceId 切片 UUID
     * @return 200 + 帧图列表
     */
    @GetMapping("/data-slices/{sliceId}/video-images")
    public ResponseEntity<?> getVideoImages(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getVideoImages request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            List<Map<String, Object>> images = videoImageService.listSliceImages(sliceId);
            return ResponseEntity.ok(images);
        } catch (Exception e) {
            log.error("getVideoImages failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("VIDEO_IMAGES_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 读取单张 5 秒帧图（JPEG）
     *
     * GET /v1/data-slices/{sliceId}/video-images/{topicSafe}/{fileName}
     *
     * @param sliceId   切片 UUID
     * @param topicSafe 清洗后的 camera topic
     * @param fileName  帧图文件名（frame_<ns>.jpg）
     * @return 200 + image/jpeg 字节流
     */
    @GetMapping("/data-slices/{sliceId}/video-images/{topicSafe}/{fileName}")
    public ResponseEntity<?> getVideoImageFile(
            @PathVariable("sliceId") String sliceId,
            @PathVariable("topicSafe") String topicSafe,
            @PathVariable("fileName") String fileName,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.debug("getVideoImageFile request: sliceId={}, topicSafe={}, fileName={}, traceId={}",
                sliceId, topicSafe, fileName, traceId);
        try {
            byte[] bytes = videoImageService.openImage(sliceId, topicSafe, fileName).readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(bytes);
        } catch (Exception e) {
            log.warn("getVideoImageFile failed: sliceId={}, file={}", sliceId, fileName, e);
            if (e instanceof com.isim.dims.exception.StorageArchivedException) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of("IMAGE_NOT_FOUND", e.getMessage(), traceId));
        }
    }
}
