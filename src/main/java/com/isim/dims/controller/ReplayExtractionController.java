package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.ReplayExtractionTaskResponse;
import com.isim.dims.service.ReplayExtractionTaskService;
import com.isim.dims.util.TraceIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回放提取任务查询 Controller
 *
 * 对应 OpenAPI v1.3.0 path:
 *   GET /v1/replay-extractions/{taskId}
 * @feature: replay
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ReplayExtractionController {

    private final ReplayExtractionTaskService replayExtractionTaskService;

    /**
     * 查询回放提取任务状态
     *
     * GET /v1/replay-extractions/{taskId}
     *
     * 状态机：PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次）。
     *
     * @param taskId 任务 UUID
     * @return 200 + ReplayExtractionTask；任务不存在 → 404 TASK_NOT_FOUND
     */
    @GetMapping("/replay-extractions/{taskId}")
    public ResponseEntity<?> getReplayExtraction(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        log.info("getReplayExtraction request: taskId={}, traceId={}", taskId, traceId);
        try {
            ReplayExtractionTaskResponse response = replayExtractionTaskService.getTask(taskId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String msg = e.getMessage();
            log.error("getReplayExtraction failed: {}", msg, e);
            if (msg != null && msg.startsWith("TASK_NOT_FOUND:")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("TASK_NOT_FOUND", msg, traceId));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("REPLAY_EXTRACTION_FAILED", msg != null ? msg : e.toString(), traceId));
        }
    }
}
