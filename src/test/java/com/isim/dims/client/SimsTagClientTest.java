package com.isim.dims.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * SIMS 片段域标签客户端单测。
 *
 * <p>锁三件事：① 路径与 query 拼装；② 成功响应的 {@code {code,msg,data}} 信封必须解包
 * （SIMS 侧 GlobalResponseAdvice 覆盖全包，不解包必然取不到 items）；
 * ③ 上游错误要转成带 SIMS 原文的 {@link SimsRemoteException}，4xx 保原状态码、5xx 归 502。
 */
class SimsTagClientTest {

    private static final String BASE = "http://sims-test.jmc.svc/sims/v1";
    private static final String TOKEN = "svc-token";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private MockRestServiceServer server;
    private SimsTagClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SimsTagClient(builder, BASE);
    }

    // ============ 分类树 ============

    @Test
    void categoryTree_unwrapsEnvelope() {
        server.expect(requestTo(BASE + "/tag-categories/tree?scope=FRAGMENT"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(
                        "{\"code\":200,\"msg\":\"\",\"data\":[{\"id\":\"c1\",\"name\":\"根\",\"nodeType\":\"CUSTOM\"}]}", JSON));

        JsonNode tree = client.categoryTree(TOKEN);

        assertThat(tree.isArray()).isTrue();
        assertThat(tree.get(0).path("id").asText()).isEqualTo("c1");
        server.verify();
    }

    @Test
    void categoryTree_toleratesMissingEnvelope() {
        server.expect(requestTo(BASE + "/tag-categories/tree?scope=FRAGMENT"))
                .andRespond(withSuccess("[{\"id\":\"c1\"},{\"id\":\"c2\"}]", JSON));

        JsonNode tree = client.categoryTree(TOKEN);

        assertThat(tree).hasSize(2);
    }

    // ============ 标签列表 ============

    @Test
    void listTags_passesAllQueryParamsInOrder() {
        // 刻意用 ASCII keyword：MockRestServiceServer 的 requestTo(String) 做 URI 字面量比较，
        // 带非 ASCII 时比对的是「已编码 vs 未编码」，会把编码细节混进这条断言。
        // 中文编码由 Spring 的 UriComponents 负责，另有生产联调覆盖。
        server.expect(requestTo(BASE + "/tags?scope=FRAGMENT&page=2&pageSize=50&categoryId=c1&keyword=road&nodeType=CUSTOM"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"code\":200,\"msg\":\"\",\"data\":{\"items\":[{\"id\":\"t1\"}],\"total\":1}}", JSON));

        JsonNode page = client.listTags(TOKEN, 2, 50, "c1", "road", "CUSTOM");

        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("total").asInt()).isEqualTo(1);
        server.verify();
    }

    @Test
    void listTags_omitsBlankOptionalParams() {
        server.expect(requestTo(BASE + "/tags?scope=FRAGMENT&page=1&pageSize=20"))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"items\":[],\"total\":0}}", JSON));

        JsonNode page = client.listTags(TOKEN, 1, 20, null, "  ", null);

        assertThat(page.path("items")).isEmpty();
        server.verify();
    }

    // ============ 标签写 ============

    @Test
    void createTag_postsBodyAndUnwraps() {
        server.expect(requestTo(BASE + "/tags?scope=FRAGMENT"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.categoryId").value("c1"))
                .andExpect(jsonPath("$.name").value("高速"))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"id\":\"t-new\",\"name\":\"高速\"}}", JSON));

        JsonNode created = client.createTag(TOKEN, "c1", "高速", "手动创建");

        assertThat(created.path("id").asText()).isEqualTo("t-new");
        server.verify();
    }

    @Test
    void createTag_omitsNullDescription() {
        server.expect(requestTo(BASE + "/tags?scope=FRAGMENT"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"id\":\"t-new\"}}", JSON));

        client.createTag(TOKEN, "c1", "高速", null);

        server.verify();
    }

    @Test
    void updateTag_usesUpdateSuffixPath() {
        server.expect(requestTo(BASE + "/tags/t9/update?scope=FRAGMENT"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("改名后"))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"id\":\"t9\",\"name\":\"改名后\"}}", JSON));

        JsonNode updated = client.updateTag(TOKEN, "t9", "改名后", null);

        assertThat(updated.path("name").asText()).isEqualTo("改名后");
        server.verify();
    }

    // ============ 分类写 ============

    @Test
    void createCategory_postsParentIdNameAndIsLeaf() {
        server.expect(requestTo(BASE + "/tag-categories?scope=FRAGMENT"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.parentId").value("c1"))
                .andExpect(jsonPath("$.name").value("子分组"))
                .andExpect(jsonPath("$.isLeaf").value(false))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"id\":\"c-new\"}}", JSON));

        JsonNode created = client.createCategory(TOKEN, "c1", "子分组", false);

        assertThat(created.path("id").asText()).isEqualTo("c-new");
        server.verify();
    }

    @Test
    void renameCategory_usesRenameSuffixPath() {
        server.expect(requestTo(BASE + "/tag-categories/c1/rename?scope=FRAGMENT"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("新名"))
                .andRespond(withSuccess("{\"code\":200,\"data\":null}", JSON));

        client.renameCategory(TOKEN, "c1", "新名");

        server.verify();
    }

    // ============ 删除（走用户 token，错误必须带 SIMS 原文） ============

    @Test
    void removeTag_403_throwsSimsRemoteExceptionWithUpstreamMessage() {
        server.expect(requestTo(BASE + "/tags/t1/remove?scope=FRAGMENT"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body("{\"code\":403,\"msg\":\"无权访问该资源\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.removeTag("user-token", "t1"))
                .isInstanceOf(SimsRemoteException.class)
                .satisfies(ex -> {
                    SimsRemoteException e = (SimsRemoteException) ex;
                    assertThat(e.getStatus()).isEqualTo(403);
                    assertThat(e.getMessage()).isEqualTo("无权访问该资源");
                });
    }

    @Test
    void removeCategory_5xx_isNormalizedTo502() {
        server.expect(requestTo(BASE + "/tag-categories/c1/remove?scope=FRAGMENT"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.removeCategory(TOKEN, "c1"))
                .isInstanceOf(SimsRemoteException.class)
                .satisfies(ex -> assertThat(((SimsRemoteException) ex).getStatus()).isEqualTo(502));
    }

    @Test
    void removeTag_nonJsonErrorBody_fallsBackToRawBody() {
        server.expect(requestTo(BASE + "/tags/t1/remove?scope=FRAGMENT"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .body("分类下有子分类")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.removeTag(TOKEN, "t1"))
                .isInstanceOf(SimsRemoteException.class)
                .hasMessageContaining("分类下有子分类");
    }

    @Test
    void categoryTree_5xx_throwsSimsRemoteException() {
        server.expect(requestTo(BASE + "/tag-categories/tree?scope=FRAGMENT"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.categoryTree(TOKEN))
                .isInstanceOf(SimsRemoteException.class);
    }

    // ============ 连接层失败：文案必须能直接定位根因 ============

    /**
     * 真实故障复现（2026-09-23 测试环境）：
     * {@code dims.sims.base-url} 用了宿主机/网关对外别名 {@code www.jmc.svc}，
     * 该名字在 dims-server 所在容器内不存在 → UnknownHostException。
     * 但原始文案 {@code I/O error on GET request for "...": www.jmc.svc} 把
     * 「DNS 解析失败」伪装成了「上游服务报错」，运维只能靠猜。
     *
     * <p>据此锁定文案必须同时说清：① 是解析失败；② 哪个主机名；
     * ③ 当前生效的 base-url；④ 覆盖途径（环境变量名）。
     */
    @Test
    void listTags_dnsResolutionFailure_messageNamesHostAndEffectiveBaseUrl() {
        server.expect(requestTo(BASE + "/tags?scope=FRAGMENT&page=1&pageSize=20"))
                .andRespond(withException(new UnknownHostException("www.jmc.svc")));

        assertThatThrownBy(() -> client.listTags(TOKEN, 1, 20, null, null, null))
                .isInstanceOf(SimsRemoteException.class)
                .satisfies(ex -> {
                    SimsRemoteException e = (SimsRemoteException) ex;
                    assertThat(e.getStatus()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("解析失败");
                    assertThat(e.getMessage()).contains("www.jmc.svc");
                    assertThat(e.getMessage()).contains(BASE);
                    assertThat(e.getMessage()).contains("DIMS_SIMS_BASE_URL");
                });
    }

    /** 非 DNS 的连接失败（超时/拒绝）不得谎报成「解析失败」，仍走通用不可达文案。 */
    @Test
    void listTags_connectTimeout_keepsGenericUnreachableMessage() {
        server.expect(requestTo(BASE + "/tags?scope=FRAGMENT&page=1&pageSize=20"))
                .andRespond(withException(new java.net.ConnectException("Connection timed out")));

        assertThatThrownBy(() -> client.listTags(TOKEN, 1, 20, null, null, null))
                .isInstanceOf(SimsRemoteException.class)
                .satisfies(ex -> {
                    SimsRemoteException e = (SimsRemoteException) ex;
                    assertThat(e.getStatus()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("SIMS 不可达");
                    assertThat(e.getMessage()).doesNotContain("解析失败");
                });
    }

    /**
     * 服务 token 未就绪（IAMS 刷新失败且未配 fallback）时必须**快速失败**，
     * 而不是发出 {@code Authorization: Bearer null}。
     *
     * <p>真实故障复现（2026-09-23 测试环境）：那个必然无效的请求会被 SIMS 回一个
     * {@code 401「未登录或登录已过期」} —— 文案把人引向「用户登录态过期」，
     * 而真实原因是**服务间凭证没拿到**，排查方向完全跑偏。
     * 这里锁：不发请求、直接给指明凭证的错误。
     */
    @Test
    void listTags_blankServiceToken_failsFastWithoutCallingSims() {
        assertThatThrownBy(() -> client.listTags("", 1, 20, null, null, null))
                .isInstanceOf(SimsRemoteException.class)
                .satisfies(ex -> {
                    SimsRemoteException e = (SimsRemoteException) ex;
                    assertThat(e.getStatus()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("服务 token");
                });
    }
}
