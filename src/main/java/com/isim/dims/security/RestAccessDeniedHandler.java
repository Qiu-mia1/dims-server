package com.isim.dims.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 已认证但无权限请求统一 403 响应
 *
 * <p>按宪法第 7 节统一错误结构返回：
 * {@code { "error": { "code": "FORBIDDEN", "message": "...", "trace_id": "..." } }}。</p>
 *
 * @feature: infra
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String traceId = TraceIdUtil.resolve(request.getHeader("X-Trace-Id"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of("FORBIDDEN", "Insufficient permissions", traceId));
    }
}
