package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.FeatureImageSearchRequest;
import com.isim.dims.dto.TextImageSearchRequest;
import com.isim.dims.service.ImageSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 帧图语义检索 Controller（以文搜图 / 以图搜图 / 特征搜图）
 *
 * <p>对应 OpenAPI paths:
 * <ul>
 *   <li>POST /v1/search-images/text</li>
 *   <li>POST /v1/search-images/by-image</li>
 *   <li>POST /v1/search-images/by-feature</li>
 * </ul>
 * 未启用 dims.siglip.enabled 时返回 503 FEATURES_DISABLED。</p>
 * @feature: visualization
 */
@Slf4j
@RestController
@RequestMapping("/v1/search-images")
@RequiredArgsConstructor
public class ImageSearchController {

    private final ImageSearchService imageSearchService;

    /**
     * 以文搜图
     *
     * POST /v1/search-images/by-text
     */
    @PostMapping("/by-text")
    public ResponseEntity<?> searchByText(
            @RequestBody TextImageSearchRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("searchImages by-text: text={}, traceId={}",
                request == null ? null : request.getText(), traceId);
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "text is required", traceId));
        }
        try {
            List<Map<String, Object>> matches = imageSearchService.searchByText(request.getText(), request.getLimit());
            return ResponseEntity.ok(result(matches));
        } catch (Exception e) {
            return error(e, traceId);
        }
    }

    /**
     * 以图搜图
     *
     * POST /v1/search-images/by-image (multipart)
     */
    @PostMapping("/by-image")
    public ResponseEntity<?> searchByImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("searchImages(by-image): size={}, traceId={}",
                file == null ? null : file.getSize(), traceId);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "file is required", traceId));
        }
        try {
            byte[] bytes = file.getBytes();
            List<Map<String, Object>> matches = imageSearchService.searchByImage(bytes, limit);
            return ResponseEntity.ok(result(matches));
        } catch (IOException e) {
            log.error("Read uploaded image failed", e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("READ_FAILED", "读取上传图片失败", traceId));
        } catch (Exception e) {
            return error(e, traceId);
        }
    }

    /**
     * 特征搜图
     *
     * POST /v1/search-images/by-feature
     */
    @PostMapping("/by-feature")
    public ResponseEntity<?> searchByFeature(
            @RequestBody FeatureImageSearchRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("searchImages(by-feature): dims={}, traceId={}",
                request == null || request.getFeature() == null ? null : request.getFeature().length, traceId);
        if (request == null || request.getFeature() == null || request.getFeature().length == 0) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("VALIDATION_FAILED", "feature is required", traceId));
        }
        try {
            List<Map<String, Object>> matches = imageSearchService.searchByFeature(request.getFeature(), request.getLimit());
            return ResponseEntity.ok(result(matches));
        } catch (Exception e) {
            return error(e, traceId);
        }
    }

    private Map<String, Object> result(List<Map<String, Object>> matches) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", matches.size());
        resp.put("matches", matches);
        return resp;
    }

    private ResponseEntity<?> error(Exception e, String traceId) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.startsWith("FEATURES_DISABLED")) {
            return ResponseEntity.status(503)
                    .body(ErrorResponse.of("FEATURES_DISABLED", msg, traceId));
        }
        log.error("Image search failed: {}", e.getMessage(), e);
        if (msg.startsWith("VALIDATION_FAILED")) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", msg, traceId));
        }
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of("IMAGE_SEARCH_FAILED", msg, traceId));
    }
}