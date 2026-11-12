package com.isim.dims.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 客户端配置
 *
 * <p>宪法第 7 节链路追踪：调用 IAMS（及其他 Feign 服务）时透传
 * {@code X-Trace-Id}，与统一错误结构的 trace_id 对应。</p>
 *
 * <p>注意：Feign 调用需处于 HTTP 请求线程内（RequestContextHolder 有值）才会透传；
 * 异步线程/定时任务中的调用需自行传入 trace_id。</p>
 *
 * @feature: infra
 */
@Configuration
public class FeignConfig {

    /**
     * 全局 Feign RequestInterceptor：从当前请求复制 X-Trace-Id 到出站请求头
     */
    @Bean
    public RequestInterceptor traceIdPropagationInterceptor() {
        return template -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                String traceId = attrs.getRequest().getHeader("X-Trace-Id");
                if (traceId != null && !traceId.isBlank()) {
                    template.header("X-Trace-Id", traceId);
                }
            }
        };
    }
}
