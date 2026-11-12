package com.isim.dims.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.client.CurrentUserTokenResolver;
import com.isim.dims.client.SimsTagClient;
import com.isim.dims.config.SecurityConfig;
import com.isim.dims.controller.TagController;
import com.isim.dims.controller.TaskController;
import com.isim.dims.service.IngestionService;
import com.isim.dims.service.SimsTagCatalog;
import com.isim.dims.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限校验测试（加载真实 SecurityFilterChain + @EnableMethodSecurity + OAuth2 Resource Server）
 *
 * <p><b>2026-09-23 定案后的标签域分层</b>：</p>
 * <ul>
 *   <li><b>认证在 DIMS</b>：{@code /v1/tags/**} 在 URL 层要求已登录（{@code authenticated()}）
 *       —— 方法层 {@code @RequireTag*} 已随「SIMS 是唯一授权源」的定案移除；
 *       若仍 permitAll，删标签会被匿名调用；</li>
 *   <li><b>授权在 SIMS</b>：Controller 把用户 token 原样透传，SIMS 按其
 *       {@code sims:tag:*} 判定 —— DIMS 侧不再配 {@code dims:tag:*}，
 *       「只配了 DIMS、SIMS 没授权」这类半配置状态随之消失；</li>
 *   <li>非法 Bearer token → 401（验签仍然开启）；</li>
 *   <li>切片打标（{@code PUT /v1/data-slices/{id}/tags}）写的是 DIMS 自己的
 *       {@code data_slice_tag} 关联表，仍由 {@code dims:tag:write} 把关。</li>
 * </ul>
 */
@WebMvcTest(controllers = {TaskController.class, TagController.class})
@Import(SecurityConfig.class)
class ResourceServerSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_TOKEN = "user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TagService tagService;

    @MockBean
    private IngestionService ingestionService;

    @MockBean
    private SimsTagClient simsTagClient;

    @MockBean
    private SimsTagCatalog simsTagCatalog;

    @MockBean
    private CurrentUserTokenResolver currentUserTokenResolver;

    /** 树里放一个「自定义区 + 叶子」节点，允许建标签；调用凭证 = 用户 token（透传给 SIMS）。 */
    private void stubWritableLeaf() {
        when(currentUserTokenResolver.currentToken()).thenReturn(USER_TOKEN);
        try {
            when(simsTagClient.categoryTree(USER_TOKEN)).thenReturn(MAPPER.readTree(
                    "[{\"id\":\"zone\",\"name\":\"自定义标签\",\"nodeType\":\"CUSTOM\",\"isLeaf\":false,"
                            + "\"children\":[{\"id\":\"c1\",\"parentId\":\"zone\",\"name\":\"叶子\","
                            + "\"nodeType\":\"CUSTOM\",\"isLeaf\":true,\"children\":[]}]}]"));
            when(simsTagClient.createTag(USER_TOKEN, "c1", "test", null)).thenReturn(
                    MAPPER.readTree("{\"id\":\"t1\",\"name\":\"test\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ============ 读接口：URL 层放行，不要求认证 ============

    @Test
    void health_WithoutToken_Returns200() throws Exception {
        mockMvc.perform(get("/v1/health"))
                .andExpect(status().isOk());
    }

    /** 标签端点（含读）现在要求登录：授权在 SIMS，但认证必须在 DIMS 把关。 */
    @Test
    void readEndpoint_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(get("/v1/tags/tree"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readEndpoint_WithTamperedToken_Returns401() throws Exception {
        // 已恢复 JWT 验签：非法 token 在 BearerTokenAuthenticationFilter 阶段即被拒绝
        mockMvc.perform(get("/v1/tags/tree")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readEndpoint_WithValidJwt_Returns200() throws Exception {
        mockMvc.perform(get("/v1/tags/tree").with(jwt()))
                .andExpect(status().isOk());
    }

    // ============ 写接口：要求认证 + 对应权限点 ============

    @Test
    void writeEndpoint_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(post("/v1/tags")
                        .contentType("application/json")
                        .content("{\"category_id\": \"c1\", \"name\": \"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** DIMS 不再按权限点拦写请求 —— 判权在 SIMS（用户 token 透传）。 */
    @Test
    void writeEndpoint_ProxiesToSims_RegardlessOfDimsAuthority() throws Exception {
        stubWritableLeaf();

        mockMvc.perform(post("/v1/tags")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_READ)))
                        .contentType("application/json")
                        .content("{\"category_id\": \"c1\", \"name\": \"test\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void writeEndpoint_WithRequiredAuthority_Returns201() throws Exception {
        stubWritableLeaf();

        mockMvc.perform(post("/v1/tags")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType("application/json")
                        .content("{\"category_id\": \"c1\", \"name\": \"test\"}"))
                .andExpect(status().isCreated());
    }

    // ============ 删除接口：粒度细化的核心回归点 ============

    @Test
    void deleteTag_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(delete("/v1/tags/t1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 2026-09-23 定案：DIMS 只要求已登录；「有 write 没 delete」是否可删，
     * 由 SIMS 按用户 token 的 {@code sims:tag:delete} 判定，DIMS 不再预拦。
     * （原先「write 不隐含 delete」的粒度约束现在由 SIMS 侧承担。）
     */
    @Test
    void deleteTag_ProxiesToSims_RegardlessOfDimsAuthority() throws Exception {
        when(currentUserTokenResolver.currentToken()).thenReturn(USER_TOKEN);

        mockMvc.perform(delete("/v1/tags/t1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE))))
                .andExpect(status().isNoContent());

        verify(simsTagClient).removeTag(eq(USER_TOKEN), eq("t1"));
    }

    @Test
    void deleteTag_WithTagDelete_Returns204() throws Exception {
        when(currentUserTokenResolver.currentToken()).thenReturn(USER_TOKEN);

        mockMvc.perform(delete("/v1/tags/t1")
                        .with(jwt()
                                .jwt(builder -> builder.tokenValue(USER_TOKEN))
                                .authorities(new SimpleGrantedAuthority(DimsPermission.TAG_DELETE))))
                .andExpect(status().isNoContent());

        verify(simsTagClient).removeTag(eq(USER_TOKEN), eq("t1"));
    }
}
