package com.isim.dims.client;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 取当前请求的原始用户 JWT，用于**透传给 SIMS**。
 *
 * <p><b>为什么标签管理全链路都用用户 token</b>（2026-09-23 定案）：
 * <ul>
 *   <li>SIMS 是标签权限的**唯一授权源**（{@code sims:tag:read/write/delete}）。
 *       DIMS 侧再配一套 {@code dims:tag:*} 只会造成「配了一边忘了另一边」的半配置态；</li>
 *   <li>client_credentials 服务 token **不带 authorities**（见 {@link SimsAccessTokenProvider}），
 *       凡按权限码判定的端点（删除、以及部署里尚未放行 service token 的读端点）
 *       用服务 token 必然 403（2026-09-23 实测）；</li>
 *   <li>透传用户 token 让 SIMS 侧审计记录**真实操作人**，而不是统一的 {@code isim-dims} 服务账号。</li>
 * </ul>
 *
 * <p><b>例外</b>：切片侧解析标签名走 {@code SimsTagCatalog}（进程内词表缓存），
 * 服务于「切片列表显示标签名」与后台无用户上下文的导入流水线，必须继续用服务 token。
 *
 * @feature: tag
 */
@Component
public class CurrentUserTokenResolver {

    /**
     * @return 当前请求 Bearer token 的原文（不含 "Bearer " 前缀）
     * @throws IllegalStateException 当前请求不是 JWT 认证（端点未鉴权或鉴权配置被改坏）
     */
    public String currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken().getTokenValue();
        }
        throw new IllegalStateException(
                "当前请求不是 JWT 认证，无法透传用户 token（端点 " + authentication + "）");
    }
}
