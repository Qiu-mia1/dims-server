package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.TaskResponse;
import com.isim.dims.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 任务与系统 Controller
 *
 * 负责导入任务进度查询和系统健康检查。
 * 对应 OpenAPI paths:
 *   GET /v1/tasks/{taskId}
 *   GET /v1/health
 * @feature: ingestion
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class TaskController {

    private final IngestionService ingestionService;

    /**
     * 查询导入任务进度
     *
     * GET /v1/tasks/{taskId}
     * 状态流转：UPLOADING -> CONVERTING(CFDI) -> PARSING -> TAGGING -> READY / ERROR
     * 响应新增 status_history 数组，记录状态变更时间线。
     *
     * @param taskId  导入任务 UUID
     * @return 200 + 导入任务详情（含 status、progress、error_msg、status_history）
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTask(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getTask request: taskId={}, traceId={}", taskId, traceId);
        try {
            TaskResponse response = ingestionService.getTask(taskId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getTask failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("TASK_NOT_FOUND", e.getMessage(), traceId));
        }
    }

    /**
     * 健康检查
     *
     * GET /v1/health
     *
     * @return 200 + { status: "UP", service: "dims", timestamp: "..." }
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        log.debug("health check");
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "dims",
                "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
        ));
    }
}
