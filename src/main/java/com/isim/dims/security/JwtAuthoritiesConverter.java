package com.isim.dims.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JWT authorities 转换器
 *
 * <p>IAMS 签发的 JWT 将权限点放在 {@code authorities} claim（字符串数组，
 * 如 {@code ["dims:slice:read", "dims:slice:write"]}），而非 Spring Security
 * 默认读取的 {@code scope} / {@code scp} claim。</p>
 *
 * <p>本转换器读取 {@code authorities} claim 并直接映射为无前缀
 * {@link GrantedAuthority}，使 {@code @PreAuthorize("hasAuthority('dims:xxx:write')")}
 * 与自定义权限注解直接生效（docs/子系统对接指南.md §3.2）。</p>
 *
 * <p>健壮性：claim 非集合（如误签发为字符串）→ 整体忽略；集合内非字符串条目
 * （数字/对象等）→ 跳过，避免把对象的 toString 当作权限误授予。</p>
 *
 * @feature: infra
 */
public class JwtAuthoritiesConverter implements Converter<Jwt, JwtAuthenticationToken> {

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object raw = jwt.getClaims().get("authorities");
        if (raw instanceof Collection<?> items) {
            for (Object item : items) {
                if (item instanceof CharSequence seq && !seq.toString().isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(seq.toString()));
                }
            }
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
