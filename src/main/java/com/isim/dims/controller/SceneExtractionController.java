package com.isim.dims.controller;

import com.isim.dims.dto.DetectedSceneDTO;
import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.SceneExtractionResponse;
import com.isim.dims.dto.SceneExtractionTaskResponse;
import com.isim.dims.dto.SceneListResponse;
import com.isim.dims.security.RequireSceneWrite;
import com.isim.dims.service.SceneExtractionPipelineService;
import com.isim.dims.util.TraceIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场景提取 Controller
 *
 * 对应 OpenAPI path:
 *   POST /v1/data-slices/{sliceId}/scene-extraction  触发场景提取（202）
 *   GET  /v1/scene-extractions/{taskId}              查询提取任务状态（200/404）
 *   GET  /v1/data-slices/{sliceId}/scenes            切片场景分页列表（200）
 *   GET  /v1/scenes/{sceneId}                        场景详情（200/404）
 *
 * 语义约定（Contract Agent DTO 注释）：场景提取为全量任务（无请求体、无参数），
 * 同切片已有进行中任务 → 409 SCENE_EXTRACTION_IN_PROGRESS（不回传旧 task_id）；
 * FAILED ≥ 2 次 → 409 SCENE_EXTRACTION_RETRY_EXCEEDED。
 * @feature: ingestion
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SceneExtractionController {

    private final SceneExtractionPipelineService sceneExtractionPipelineService;

    /**
     * 触发切片场景提取
     *
     * POST /v1/data-slices/{sliceId}/scene-extraction
     *
     * @param sliceId 切片 UUID
     * @return 202 + SceneExtractionResponse { task_id, status }；
     *         切片不存在 → 404 SLICE_NOT_FOUND；
     *         同切片有进行中任务 / 重试超限 → 409
     */
    @RequireSceneWrite
    @PostMapping("/data-slices/{sliceId}/scene-extraction")
    public ResponseEntity<?> triggerExtraction(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("triggerSceneExtraction request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            SceneExtractionResponse response = sceneExtractionPipelineService.triggerExtraction(sliceId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("triggerSceneExtraction failed: {}", msg, e);
            // 归档切片（storage_type=LOCAL + minio 模式）：数据内容消费 → 410（契约裁决 2）
            if (msg != null && msg.startsWith("DATA_ARCHIVED:")) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(ErrorResponse.of("DATA_ARCHIVED", msg, traceId));
            }
            if (msg != null && msg.startsWith("SLICE_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("SLICE_NOT_FOUND", msg, traceId));
            }
            if (msg != null && msg.startsWith("SCENE_EXTRACTION_RETRY_EXCEEDED:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ErrorResponse.of("SCENE_EXTRACTION_RETRY_EXCEEDED", msg, traceId));
            }
            if (msg != null && msg.startsWith("SCENE_EXTRACTION_IN_PROGRESS:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ErrorResponse.of("SCENE_EXTRACTION_IN_PROGRESS", msg, traceId));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("SCENE_EXTRACTION_FAILED", msg != null ? msg : e.toString(), traceId));
        }
    }

    /**
     * 查询场景提取任务状态
     *
     * GET /v1/scene-extractions/{taskId}
     *
     * @param taskId 任务 UUID
     * @return 200 + SceneExtractionTask；任务不存在 → 404 TASK_NOT_FOUND
     */
    @GetMapping("/scene-extractions/{taskId}")
    public ResponseEntity<?> getSceneExtraction(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getSceneExtraction request: taskId={}, traceId={}", taskId, traceId);
        try {
            SceneExtractionTaskResponse response = sceneExtractionPipelineService.getTask(taskId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("getSceneExtraction failed: {}", msg, e);
            if (msg != null && msg.startsWith("TASK_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("TASK_NOT_FOUND", msg, traceId));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("SCENE_EXTRACTION_FAILED", msg != null ? msg : e.toString(), traceId));
        }
    }

    /**
     * 查询切片已检测场景列表（分页，按 start_time 升序）
     *
     * GET /v1/data-slices/{sliceId}/scenes?page=1&page_size=50
     *
     * @param sliceId  切片 UUID
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 50，最大 500
     * @return 200 + SceneListResponse
     */
    @GetMapping("/data-slices/{sliceId}/scenes")
    public ResponseEntity<?> listScenes(
            @PathVariable("sliceId") String sliceId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("listScenes request: sliceId={}, page={}, pageSize={}, traceId={}",
                sliceId, page, pageSize, traceId);
        try {
            SceneListResponse response = sceneExtractionPipelineService.listScenes(sliceId, page, pageSize);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("listScenes failed: {}", msg, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("SCENE_LIST_FAILED", msg != null ? msg : e.toString(), traceId));
        }
    }

    /**
     * 读取过程文件内容
     *
     * GET /v1/data-slices/{sliceId}/scene-extraction/files/content?path={absolutePath}
     *
     * <p>安全限制：path 必须在该切片最新任务的 intermediate_paths 或 output_path 中
     * （含子路径，如 perception/ 目录下的单个文件、openscenario/*.xosc）。</p>
     *
     * @param sliceId 切片 UUID
     * @param path    文件绝对路径
     * @return 200 + 文件内容（text/plain）；路径不允许 → 403；读错误 → 500
     */
    @GetMapping("/data-slices/{sliceId}/scene-extraction/files/content")
    public ResponseEntity<?> readProcessFile(
            @PathVariable("sliceId") String sliceId,
            @RequestParam("path") String path,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("readProcessFile request: sliceId={}, path={}, traceId={}", sliceId, path, traceId);
        try {
            String content = sceneExtractionPipelineService.readProcessFile(sliceId, path);
            return ResponseEntity.ok(content);
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("readProcessFile failed: {}", msg, e);
            // 归档切片（storage_type=LOCAL + minio 模式）：数据内容读取 → 410（契约裁决 2）
            if (msg != null && msg.startsWith("DATA_ARCHIVED:")) {
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(ErrorResponse.of("DATA_ARCHIVED", msg, traceId));
            }
            if (msg != null && msg.startsWith("NO_EXTRACTION_TASK:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("NO_EXTRACTION_TASK", msg, traceId));
            }
            if (msg != null && msg.startsWith("FILE_NOT_ALLOWED:")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ErrorResponse.of("FILE_NOT_ALLOWED", msg, traceId));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("FILE_READ_ERROR", msg != null ? msg : e.toString(), traceId));
        }
    }

    /**
     * 查询切片最新场景提取任务
     *
     * GET /v1/data-slices/{sliceId}/scene-extraction/latest
     *
     * <p>前端页面刷新后恢复提取状态（无需依赖 localStorage）。
     * 返回该切片最新一条任务（含 FAILED/SUCCESS/RUNNING）；
     * 无任何提取记录时返回 404。</p>
     *
     * @param sliceId 切片 UUID
     * @return 200 + SceneExtractionTask；无任务记录 → 404 NO_EXTRACTION_TASK
     */
    @GetMapping("/data-slices/{sliceId}/scene-extraction/latest")
    public ResponseEntity<?> getLatestExtraction(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getLatestExtraction request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            SceneExtractionTaskResponse response = sceneExtractionPipelineService.getLatestTaskBySliceId(sliceId);
            if (response == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("NO_EXTRACTION_TASK",
                                "No extraction task found for slice " + sliceId, traceId));
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("getLatestExtraction failed: {}", msg, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("SCENE_EXTRACTION_FAILED", msg != null ? msg : e.toString(), traceId));
        }
    }

    /**
     * 查询单个场景详情
     *
     * GET /v1/scenes/{sceneId}
     *
     * @param sceneId 场景 UUID
     * @return 200 + DetectedScene；场景不存在 → 404 SCENE_NOT_FOUND
     */
    @GetMapping("/scenes/{sceneId}")
    public ResponseEntity<?> getScene(
            @PathVariable("sceneId") String sceneId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getScene request: sceneId={}, traceId={}", sceneId, traceId);
        try {
            DetectedSceneDTO response = sceneExtractionPipelineService.getScene(sceneId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("getScene failed: {}", msg, e);
            if (msg != null && msg.startsWith("SCENE_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("SCENE_NOT_FOUND", msg, traceId));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("SCENE_QUERY_FAILED", msg != null ? msg : e.toString(), traceId));
        }
    }
}
