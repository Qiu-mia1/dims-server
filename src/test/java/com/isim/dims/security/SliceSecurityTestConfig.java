package com.isim.dims.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code @WebMvcTest} 切片测试专用安全配置
 *
 * <p>引入 spring-security 后，{@code @WebMvcTest} 默认会套上 Spring Security 过滤链，
 * 导致未带 token 的既有 Controller 测试全部 401。本配置放行全部请求 + 关闭 CSRF，
 * 使切片测试回归"纯 Controller 行为"测试。既有测试类通过
 * {@code @Import(SliceSecurityTestConfig.class)} 引入。</p>
 *
 * <p>鉴权/权限行为本身的测试见 {@link ResourceServerSecurityTest}
 * （{@code @Import(SecurityConfig.class)} 加载真实安全链）。</p>
 */
@TestConfiguration
public class SliceSecurityTestConfig {

    @Bean
    public SecurityFilterChain sliceTestPermitAllChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
