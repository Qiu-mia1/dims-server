package com.isim.dims.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.security.JwtAuthoritiesConverter;
import com.isim.dims.security.RestAccessDeniedHandler;
import com.isim.dims.security.RestAuthenticationEntryPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 权限配置 —— URL 层放行 + 方法层权限点强制
 *
 * <p><b>分层策略（2026-09-16 恢复）</b>：</p>
 * <ul>
 *   <li><b>URL 层（认证）</b>：{@code anyRequest().permitAll()} —— 不做路径级拦截，
 *       匿名请求可直达 Controller。读接口（未加 {@code @Require*} 注解）因此保持
 *       「已登录即可 / 联调可匿名」的宽松策略，不影响本地 curl 调试。</li>
 *   <li><b>方法层（授权）</b>：{@code @EnableMethodSecurity} + 自定义
 *       {@code @RequireXxx*} 注解（内部即 {@code @PreAuthorize("hasAuthority('dims:...')")}）
 *       在 Controller 方法上强制校验权限点。<b>写/删类接口一律要求携带对应权限点</b>，
 *       普通账户未被分配即被拦截。</li>
 *   <li><b>JWT 验签</b>：注册 OAuth2 Resource Server，RS256 + IAMS JWKS 本地验签
 *       （{@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}，仅启动/定期拉取，
 *       不逐请求回查 IAMS）。{@link JwtAuthoritiesConverter} 把 JWT 的 {@code authorities}
 *       claim 映射为 {@link org.springframework.security.core.GrantedAuthority}。</li>
 * </ul>
 *
 * <p><b>响应语义</b>：</p>
 * <ul>
 *   <li>无 token / token 非法或过期 + 命中受保护方法 → <b>401</b>
 *       （由 {@link RestAuthenticationEntryPoint} 输出统一 {@code ErrorResponse}）；</li>
 *   <li>token 有效但缺少所需权限点 → <b>403</b>（由 {@link RestAccessDeniedHandler} 输出）；</li>
 *   <li>读接口无 token → 正常放行（URL 层 permitAll）。</li>
 * </ul>
 *
 * <p><b>变更历史</b>：本类曾在「去掉权限校验」提交（4bea319）中改为注入写死的
 * {@code dev-bypass} 调试身份并关闭验签，配套把各 Controller 的 {@code @RequireXxx*}
 * 注解整体注释（0809916）。两条都已随本次「删除权限粒度细化」一并还原——
 * 否则权限点配置得再细也不生效。</p>
 *
 * <p>CSRF 关闭 + STATELESS Session：无状态 JWT 场景标准做法。</p>
 *
 * <p>test profile 下本配置整体禁用（{@code @Profile("!test")}）：</p>
 * <ul>
 *   <li>全上下文集成测试由 {@code src/test/.../security/TestProfileSecurityConfig} 提供放行链；</li>
 *   <li>{@code @WebMvcTest} 切片测试由 {@code SliceSecurityTestConfig} 提供放行链；</li>
 *   <li>鉴权行为断言测试 {@code ResourceServerSecurityTest} 显式 {@code @Import(SecurityConfig.class)}
 *       加载真实安全链。</li>
 * </ul>
 *
 * @feature: infra
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
@Slf4j
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                                                   RestAccessDeniedHandler restAccessDeniedHandler) throws Exception {
        http
                // 无状态 JWT：关闭 CSRF（否则现有 POST 接口全部 403）
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // URL 层放行：不做路径级拦截，权限判定下沉到方法层 @PreAuthorize
                // URL 层：
                //  - 标签管理端点要求【已登录】。授权本身交给 SIMS（透传用户 token，按其
                //    sims:tag:* 判定），但「是否登录」必须在这一层把关 —— 方法层不再放
                //    @RequireTag* 之后，permitAll 会让匿名请求直达业务（删标签可被匿名调用）。
                //  - 其余端点不做路径级拦截，权限判定下沉到方法层 @PreAuthorize。
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/tags", "/v1/tags/**").authenticated()
                        .anyRequest().permitAll())
                // OAuth2 Resource Server：恢复 JWT 验签 + authorities claim → GrantedAuthority
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthoritiesConverter())))
                .exceptionHandling(exception -> exception
                        // 未认证（无 / 非法 token）→ 401
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        // 已认证但权限点不足（方法级 @PreAuthorize 拒绝）→ 403
                        .accessDeniedHandler(restAccessDeniedHandler));

        log.info("【DIMS 安全配置】JWT 验签已启用（IAMS JWKS）；URL 层放行，写/删接口由方法级 @PreAuthorize 强制校验权限点");
        return http.build();
    }

    @Bean
    public JwtAuthoritiesConverter jwtAuthoritiesConverter() {
        return new JwtAuthoritiesConverter();
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new RestAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return new RestAccessDeniedHandler(objectMapper);
    }
}
