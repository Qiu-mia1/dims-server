package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.ExportListResponse;
import com.isim.dims.dto.ExportRequest;
import com.isim.dims.dto.ExportResponse;
import com.isim.dims.exception.SliceNotFoundException;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.security.RequireExportRead;
import com.isim.dims.security.RequireExportWrite;
import com.isim.dims.service.ExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 数据导出 Controller
 *
 * 负责按时间窗口和 Topic 列表裁剪 bag 文件，生成导出产物供下游下载。
 * 对应 OpenAPI paths:
 *   POST /v1/data-slices/{sliceId}/exports
 *   GET  /v1/data-slices/{sliceId}/exports
 *   GET  /v1/exports
 *   GET  /v1/exports/{exportId}
 * @feature: export
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ExportController {

    /** 合法的导出任务状态枚举（W4：status 过滤参数校验） */
    private static final Set<String> VALID_EXPORT_STATUSES =
            Set.of("PENDING", "PROCESSING", "READY", "ERROR", "EXPIRED");

    private final ExportService exportService;

    /**
     * 创建导出任务
     *
     * POST /v1/data-slices/{sliceId}/exports
     * 相同 (sliceId, start_ts, end_ts, topics, format) 的请求复用已有导出产物。
     *
     * @param sliceId 切片 UUID
     * @param request 导出请求参数（时间窗口、Topic 列表、格式）
     * @return 202 + 导出任务详情
     */
    @RequireExportWrite
    @PostMapping("/data-slices/{sliceId}/exports")
    public ResponseEntity<?> createExport(
            @PathVariable("sliceId") String sliceId,
            @Valid @RequestBody ExportRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("createExport request: sliceId={}, request={}, traceId={}", sliceId, request, traceId);
        try {
            ExportResponse response = exportService.createExport(sliceId, request);
            return ResponseEntity.status(202).body(response);
        } catch (StorageArchivedException e) {
            // 归档切片拦截：minio 模式 + storage_type=LOCAL → 410 DATA_ARCHIVED
            // （必须置于 generic catch 之前，否则会被吞成 500）
            log.warn("createExport rejected (archived slice): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("createExport failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("EXPORT_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 查询导出任务状态和下载链接
     *
     * GET /v1/exports/{exportId}
     *
     * @param exportId 导出任务 UUID
     * @return 200 + 导出任务详情
     */
    @GetMapping("/exports/{exportId}")
    public ResponseEntity<?> getExportStatus(
            @PathVariable("exportId") String exportId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getExportStatus request: exportId={}, traceId={}", exportId, traceId);
        try {
            ExportResponse response = exportService.getStatus(exportId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getExportStatus failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("EXPORT_NOT_FOUND", e.getMessage(), traceId));
        }
    }

    /**
     * 查询指定切片的所有导出任务
     *
     * GET /v1/data-slices/{sliceId}/exports
     *
     * @param sliceId 切片 UUID
     * @return 200 + 导出任务列表（按创建时间倒序）
     */
    @GetMapping("/data-slices/{sliceId}/exports")
    public ResponseEntity<?> listExports(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("listExports request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            List<ExportResponse> exports = exportService.listBySliceId(sliceId);
            return ResponseEntity.ok(exports);
        } catch (Exception e) {
            log.error("listExports failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("EXPORT_QUERY_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 全局导出任务列表（分页，按创建时间倒序）
     *
     * GET /v1/exports
     * 可选按 slice_id / status 过滤；slice_id 不存在时返回 404。
     *
     * @param sliceId  切片 UUID（可选）
     * @param status   任务状态（可选，PENDING|PROCESSING|READY|ERROR|EXPIRED）
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 20，范围 [1, 100]
     * @param traceId 链路追踪 ID
     * @return 200 + 分页导出任务列表
     */
    @GetMapping("/exports")
    public ResponseEntity<?> listExportTasks(
            @RequestParam(value = "slice_id", required = false) String sliceId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("listExportTasks request: sliceId={}, status={}, page={}, pageSize={}, traceId={}",
                sliceId, status, page, pageSize, traceId);
        // W4：status 过滤参数枚举校验，非法值返回 400 INVALID_PARAMETER
        if (status != null && !status.isBlank() && !VALID_EXPORT_STATUSES.contains(status)) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("INVALID_PARAMETER",
                            "status must be one of PENDING|PROCESSING|READY|ERROR|EXPIRED, got=" + status,
                            traceId));
        }
        // 参数归一化：page 最小 1，page_size 限制在 [1, 100]
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        try {
            ExportListResponse response = exportService.listExports(sliceId, status, safePage, safePageSize);
            return ResponseEntity.ok(response);
        } catch (SliceNotFoundException e) {
            // W1：类型化异常捕获，替代字符串前缀判定
            log.warn("listExportTasks slice not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of("SLICE_NOT_FOUND", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("listExportTasks failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("EXPORT_QUERY_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 下载导出文件
     *
     * GET /v1/exports/{exportId}/download
     * 返回文件二进制流，Content-Disposition: attachment
     *
     * @param exportId 导出任务 UUID
     * @param traceId 链路追踪 ID
     * @return 文件二进制流
     */
    @RequireExportRead
    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<StreamingResponseBody> downloadExport(
            @PathVariable("exportId") String exportId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("downloadExport request: exportId={}, traceId={}", exportId, traceId);
        // 流式转发：不再全量读入内存（大文件导出）
        // （返回类型必须为具体泛型 ResponseEntity<StreamingResponseBody>，
        //   ResponseEntity<?> 会使 StreamingResponseBodyReturnValueHandler 失配 → 500 "No converter"；
        //   StorageArchivedException / 其他异常经下方 @ExceptionHandler 映射错误响应）
        ExportService.DownloadContent content = exportService.download(exportId);
        String fileName = exportService.getExportFileName(exportId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build());
        headers.setContentLength(content.size());
        StreamingResponseBody body = outputStream -> {
            try (InputStream is = content.stream()) {
                is.transferTo(outputStream);
                outputStream.flush();
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * 归档切片拦截：storage_type=LOCAL + minio 模式读取数据内容 → 410 DATA_ARCHIVED
     * （契约裁决 2：HTTP 410 + ErrorResponse.of 结构）
     */
    @ExceptionHandler(StorageArchivedException.class)
    public ResponseEntity<ErrorResponse> handleArchived(StorageArchivedException e,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.warn("downloadExport rejected (archived slice): message={}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ErrorResponse.of("DATA_ARCHIVED", e.getMessage(), traceId));
    }

    /**
     * downloadExport 兜底异常 → 500 DOWNLOAD_FAILED
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleDownloadError(Exception e,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.error("downloadExport failed: {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(ErrorResponse.of("DOWNLOAD_FAILED", e.getMessage(), traceId));
    }
}
