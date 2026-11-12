package com.isim.dims.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthoritiesConverter 单元测试
 *
 * <p>验证 IAMS JWT 的 {@code authorities} claim 到 Spring GrantedAuthority 的映射
 * （docs/子系统对接指南.md §3.2：权限点无前缀直读）。</p>
 */
class JwtAuthoritiesConverterTest {

    private final JwtAuthoritiesConverter converter = new JwtAuthoritiesConverter();

    private Jwt jwtWithClaims(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0000-000000000010")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    void authoritiesClaim_MappedWithoutPrefix() {
        Jwt jwt = jwtWithClaims(Map.of("authorities", List.of("dims:slice:read", "dims:slice:write")));

        JwtAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("dims:slice:read", "dims:slice:write");
        // JwtAuthenticationToken 的 principal 为 Jwt 本体（sub 可经 getTokenAttributes 获取）
        assertThat(token.getTokenAttributes().get("sub"))
                .isEqualTo("00000000-0000-0000-0000-000000000010");
    }

    @Test
    void missingAuthoritiesClaim_ResultsEmptyAuthorities() {
        // 仅 scope claim（Spring Security 默认读取），authorities 缺失 → 空权限
        Jwt jwt = jwtWithClaims(Map.of("scope", List.of("read", "write")));

        JwtAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void blankOrNullEntries_AreSkipped() {
        Jwt jwt = jwtWithClaims(Map.of(
                "authorities", Arrays.asList("dims:tag:write", "", null, "  ")));

        JwtAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("dims:tag:write");
    }

    @Test
    void authoritiesClaim_AsPlainString_IsIgnored() {
        // IAMS 误签发 authorities 为字符串（非数组）→ 整体忽略，不当作单条权限
        Jwt jwt = jwtWithClaims(Map.of("authorities", "dims:slice:read"));

        JwtAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void nonStringEntries_AreSkipped() {
        // 集合内混入数字/对象/null → 跳过，只保留字符串权限
        Jwt jwt = jwtWithClaims(Map.of(
                "authorities", Arrays.asList("dims:tag:write", 123, Map.of("k", "v"), null)));

        JwtAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("dims:tag:write");
    }
}
