package com.isim.dims.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code @SpringBootTest} + profile=test 集成测试（如 DataSliceIntegrationTest）
 * 专用安全配置：放行全部请求 + 关闭 CSRF。
 *
 * <p>集成测试聚焦业务流水线（上传→解析→打标→导出），不关心鉴权；
 * 真实安全链在 profile=test 下被本链（@Order(0)）优先匹配，保持既有测试行为不变。</p>
 */
@Configuration
@Profile("test")
public class TestProfileSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain testProfilePermitAllChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
