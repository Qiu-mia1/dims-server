package com.isim.dims.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * IAMS 服务 token 供应者单测。
 *
 * <p>核心语义：token 刷新失败**绝不能**清空已有 token
 * （否则 SIMS 代理全线 401/403），必须保留 fallback。
 */
class SimsAccessTokenProviderTest {

    private static final String TOKEN_URL = "http://iams-test.jmc.svc:8080/iams/oauth2/token";
    private static final String FALLBACK = "fallback-token";

    private MockRestServiceServer server;
    private SimsAccessTokenProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new SimsAccessTokenProvider(builder, TOKEN_URL, "isim-dims", "isim-dims-secret", FALLBACK, 10800000L);
        provider.initialize();
    }

    @Test
    void initialize_usesConfiguredFallbackToken() {
        assertThat(provider.token()).isEqualTo(FALLBACK);
    }

    @Test
    void refreshToken_success_replacesWithFreshToken() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", startsWith("Basic ")))
                .andRespond(withSuccess("{\"access_token\":\"fresh-token\"}", MediaType.APPLICATION_JSON));

        provider.refreshToken();

        assertThat(provider.token()).isEqualTo("fresh-token");
        server.verify();
    }

    @Test
    void refreshToken_iamsUnreachable_keepsFallbackAndDoesNotThrow() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatCode(provider::refreshToken).doesNotThrowAnyException();

        assertThat(provider.token()).isEqualTo(FALLBACK);
    }

    @Test
    void refreshToken_responseWithoutAccessToken_keepsFallback() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatCode(provider::refreshToken).doesNotThrowAnyException();

        assertThat(provider.token()).isEqualTo(FALLBACK);
    }

    @Test
    void refreshToken_blankAccessToken_keepsFallback() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"   \"}", MediaType.APPLICATION_JSON));

        assertThatCode(provider::refreshToken).doesNotThrowAnyException();

        assertThat(provider.token()).isEqualTo(FALLBACK);
    }

    /**
     * 首次刷新**不得**被推迟到 3 小时。
     *
     * <p>真实故障（2026-09-23 测试环境）：{@code initialDelayString} 复用了
     * {@code token-refresh-ms}(10800000=3h)，而 {@link SimsAccessTokenProvider#initialize()}
     * 把 accessToken 初始化成配置里的 fallback token —— 那个 token 当时已过期 33 天。
     * 结果是**启动后 3 小时内**所有 SIMS 调用都带过期 token，
     * 接口整片 401「未登录或登录已过期」，且真正的「凭证来路」完全没被暴露。
     */
    @Test
    void refreshSchedule_firstRefreshIsNotDeferredByThreeHours() throws Exception {
        Scheduled scheduled = SimsAccessTokenProvider.class
                .getMethod("refreshToken")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString()).doesNotContain("10800000");
        assertThat(scheduled.initialDelayString()).contains("token-initial-delay-ms");
        assertThat(scheduled.fixedRateString()).contains("token-refresh-ms");
    }
}
