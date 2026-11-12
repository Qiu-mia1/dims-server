package com.isim.dims.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 未认证请求统一 401 响应
 *
 * <p>覆盖 Spring Security 默认的空 body 401，按宪法第 7 节统一错误结构返回：
 * {@code { "error": { "code": "UNAUTHORIZED", "message": "...", "trace_id": "..." } }}。</p>
 *
 * @feature: infra
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String traceId = TraceIdUtil.resolve(request.getHeader("X-Trace-Id"));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of("UNAUTHORIZED", "Authentication required: missing or invalid token", traceId));
    }
}
