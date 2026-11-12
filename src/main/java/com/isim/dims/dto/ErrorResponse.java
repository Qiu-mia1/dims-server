package com.isim.dims.dto;

import com.isim.dims.util.TraceIdUtil;
import lombok.Data;

/**
 * 统一错误结构
 *
 * 对应 iSim3.0 平台宪法第 7 节 及 OpenAPI schema: Error。
 * 格式：{ "error": { "code": "STRING_CODE", "message": "human readable" } }
 * @feature: infra
 */
@Data
public class ErrorResponse {

    /** 错误详情 */
    private ErrorDetail error;

    /**
     * 创建标准错误响应
     *
     * @param code    错误码，如 "SLICE_NOT_FOUND"、"UPLOAD_FAILED"
     * @param message 可读的错误描述
     * @return ErrorResponse 实例
     */
    public static ErrorResponse of(String code, String message) {
        return of(code, message, null);
    }

    public static ErrorResponse of(String code, String message, String traceId) {
        ErrorResponse response = new ErrorResponse();
        ErrorDetail detail = new ErrorDetail();
        detail.setCode(code);
        detail.setMessage(message);
        // 契约 v1.3.1：trace_id 恒为合法 UUID（缺失/非法占位值 → 生成，见 TraceIdUtil）
        detail.setTraceId(TraceIdUtil.resolve(traceId));
        response.setError(detail);
        return response;
    }

    /**
     * 错误详情
     */
    @Data
    public static class ErrorDetail {

        /** 错误码，如 "SLICE_NOT_FOUND"、"UPLOAD_FAILED" */
        private String code;

        /** 可读的错误描述 */
        private String message;

        /** 链路追踪 ID（对应请求头 X-Trace-Id） */
        private String traceId;
    }
}
