package com.isim.dims.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路追踪 ID 工具
 *
 * <p>契约 v1.3.1 / 宪法第 7 节：统一错误结构的 trace_id 为字符串 UUID
 * （OpenAPI Error.trace_id format: uuid）。请求头 X-Trace-Id 缺失或非法时，
 * 生成 UUID 兜底，保证进入错误响应的 trace_id 恒为合法 UUID。</p>
 * @feature: infra
 */
public final class TraceIdUtil {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private TraceIdUtil() {
    }

    /**
     * 规范化 trace_id：合法 UUID 透传（网关/上游生成的链路 ID 保持原值），
     * 缺失、空串或非法值（如历史占位符 "auto-generated"）→ 生成新 UUID。
     */
    public static String resolve(String traceId) {
        if (traceId != null && UUID_PATTERN.matcher(traceId).matches()) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }
}
