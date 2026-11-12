package com.isim.dims.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;

/**
 * IAMS {@code client_credentials} 服务 token 供应者。
 *
 * <p>由原「SIMS 标签目录定时同步服务」的 token 部分抽出：标签目录同步已改为实时代理
 * （本地 {@code tag} 表随 V29 移除），但服务 token 的生命周期与「目录」无关，仍需独立刷新。
 *
 * <p><b>⚠️ 服务 token 的能力边界</b>：client_credentials token <b>不带 authorities</b>
 * （JWT 仅含 {@code login_type=client_credentials} 与 {@code sub=client_id}）。
 * SIMS 侧 {@code TagScopeGuard} 对读/建/改/移端点显式放行 service token，
 * 但**删除端点**要求真实持有 {@code sims:tag:delete} —— 服务 token 必然 403。
 * 因此删除类调用必须改用用户 token，见 {@code CurrentUserTokenResolver}。
 *
 * <p>IAMS 不可达时回落到配置的 fallback token，避免本地/短时网络抖动导致标签功能整体不可用。
 *
 * @feature: tag
 */
@Slf4j
@Component
public class SimsAccessTokenProvider {

    private final RestClient.Builder restClientBuilder;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String fallbackToken;

    private volatile String accessToken;

    public SimsAccessTokenProvider(RestClient.Builder restClientBuilder,
                                   @Value("${iams.client.token-url}") String tokenUrl,
                                   @Value("${iams.client.id}") String clientId,
                                   @Value("${iams.client.secret}") String clientSecret,
                                   @Value("${dims.sims.fallback-token:}") String fallbackToken,
                                   @Value("${dims.sims.token-refresh-ms:10800000}") long tokenRefreshMs) {
        this.restClientBuilder = restClientBuilder;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.fallbackToken = fallbackToken;
        // tokenRefreshMs 仅用于 @Scheduled 属性占位，构造期不需要持有 → 忽略
        log.debug("SimsAccessTokenProvider configured: tokenUrl={}, refreshMs={}", tokenUrl, tokenRefreshMs);
    }

    @PostConstruct
    public void initialize() {
        // 启动即持有 fallback token：网络刷新按 schedule 进行，
        // 本地测试/离线开发不阻塞启动，也不会出现「启动瞬间无 token」窗口。
        //
        // ⚠️ fallback-token 默认已置空（application.yml），且首次刷新延迟默认 0 —— 两者配套：
        // 曾经 initialDelayString 复用了 token-refresh-ms(3h)，而硬编码的 fallback token
        // 一旦过期，就会导致**启动后 3 小时内**所有 SIMS 调用带过期 token 返回 401
        // 「未登录或登录已过期」（2026-09-23 实测踩坑，fallback 当时已过期 33 天）。
        // 现在：IAMS 正常 → 秒级拿到真 token；IAMS 不可达 → 明确「凭证未就绪」而不是静默 401。
        accessToken = fallbackToken;
    }

    /** 当前可用的服务 token；可能为 null（未配置 fallback 且从未刷新成功）。 */
    public String token() {
        return accessToken;
    }

    @Scheduled(
            fixedRateString = "${dims.sims.token-refresh-ms:10800000}",
            initialDelayString = "${dims.sims.token-initial-delay-ms:0}")
    public void refreshToken() {
        try {
            JsonNode response = restClientBuilder.build()
                    .post()
                    .uri(tokenUrl)
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .body(JsonNode.class);
            String token = response == null ? null : response.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("IAMS response does not contain access_token");
            }
            accessToken = token;
            log.info("IAMS access token refreshed successfully");
        } catch (Exception ex) {
            // ⚠️ 刷新失败绝不能清空已有 token：清空会让标签功能整体 401/403，
            // 而这只是「3 小时一次」的例行刷新，用旧 token 继续跑远好过全线不可用。
            if (fallbackToken == null || fallbackToken.isBlank()) {
                log.error("IAMS token refresh failed and no fallback token is configured", ex);
                return;
            }
            accessToken = fallbackToken;
            log.warn("IAMS token refresh failed; using configured fallback token: {}", ex.getMessage());
        }
    }
}
