package com.isim.dims.controller;

import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.VinMd5ImportResponse;
import com.isim.dims.service.VinMd5ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 车辆信息（VIN-MD5 映射）Controller
 * <p>
 * POST /v1/vin-md5-mappings/import
 * multipart/form-data
 * <p>
 * 上传含 vin、md5 两列的 Excel（列名忽略大小写），按 VIN 批量写入/覆盖
 * {@code vin_md5_mapping}，供本地目录导入按 MD5 子目录反查 VIN 使用。
 *
 * @feature: ingestion
 */
@Slf4j
@RestController
@RequestMapping("/v1/vin-md5-mappings")
@RequiredArgsConstructor
public class VinMd5MappingController {

    private final VinMd5ImportService vinMd5ImportService;

    /**
     * 导入车辆信息（VIN-MD5 映射）
     *
     * @param file    含 vin、md5 两列的 Excel（.xlsx / .xls）
     * @param traceId 链路追踪 ID
     * @return 200 + { total_rows, imported_count, failed_count, failed: [ { row, vin, md5, reason } ] }
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importVinMd5(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("vin-md5 import request: fileName={}, size={}, traceId={}",
                file == null ? null : file.getOriginalFilename(),
                file == null ? 0 : file.getSize(),
                traceId);
        try {
            VinMd5ImportResponse response = vinMd5ImportService.importExcel(file);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("vin-md5 import rejected: msg={}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of(resolveErrorCode(e.getMessage(), "INVALID_FILE"), e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("vin-md5 import failed: msg={}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of(resolveErrorCode(e.getMessage(), "IMPORT_FAILED"), e.getMessage(), traceId));
        }
    }

    /**
     * 从「CODE: message」形式的异常消息中取出错误码（无冒号时回退默认码）
     */
    private static String resolveErrorCode(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        int idx = message.indexOf(':');
        String code = idx > 0 ? message.substring(0, idx) : fallback;
        return code.isBlank() ? fallback : code;
    }
}
