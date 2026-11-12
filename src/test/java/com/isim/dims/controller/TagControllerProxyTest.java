package com.isim.dims.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.client.CurrentUserTokenResolver;
import com.isim.dims.client.SimsRemoteException;
import com.isim.dims.client.SimsTagClient;
import com.isim.dims.config.SecurityConfig;
import com.isim.dims.dto.SliceTagResponse;
import com.isim.dims.dto.TagRef;
import com.isim.dims.security.DimsPermission;
import com.isim.dims.service.SimsTagCatalog;
import com.isim.dims.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标签代理 Controller 切片测试（加载真实安全链，见 {@link SecurityConfig}）。
 *
 * <p>锁三组语义：
 * <ol>
 *   <li><b>读放开</b>：树/列表/下拉匿名可访问（无 {@code @Require*} 注解）；</li>
 *   <li><b>写删鉴权</b>：{@code dims:tag:write} / {@code dims:tag:delete}，且权限不足时
 *       <b>一次都不碰 SIMS</b>（不能靠 SIMS 兜底）；</li>
 *   <li><b>删除必须透传用户 token</b>：client_credentials 服务 token 不带 authorities，
 *       SIMS 删除端点只认 {@code sims:tag:delete} → 用服务 token 必然 403。
 *       这是本模块最容易被"顺手统一"改坏的一点，必须显式回归。</li>
 * </ol>
 */
@WebMvcTest(TagController.class)
@Import(SecurityConfig.class)
class TagControllerProxyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_TOKEN = "user-token-123";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SimsTagClient simsTagClient;

    @MockBean
    private SimsTagCatalog simsTagCatalog;

    @MockBean
    private CurrentUserTokenResolver currentUserTokenResolver;

    @MockBean
    private TagService tagService;

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造「域根 → 分区 → 叶子」三层树，叶子 nodeType 可指定。 */
    private void stubTree(String leafNodeType, boolean leafIsLeaf) {
        String zone = "CUSTOM".equals(leafNodeType) ? "自定义标签" : "内置标签";
        when(simsTagClient.categoryTree(USER_TOKEN)).thenReturn(json(
                "[{\"id\":\"root\",\"name\":\"片段标签\",\"nodeType\":\"BUILTIN\",\"is_leaf\":false,\"children\":["
                        + "{\"id\":\"zone\",\"parentId\":\"root\",\"name\":\"" + zone + "\","
                        + "\"nodeType\":\"" + leafNodeType + "\",\"is_leaf\":false,\"children\":["
                        + "{\"id\":\"c1\",\"parentId\":\"zone\",\"name\":\"叶子分类\","
                        + "\"nodeType\":\"" + leafNodeType + "\",\"isLeaf\":" + leafIsLeaf + ",\"children\":[]}]}]}]"));
    }

    /**
     * 让「严格透传用户 token」生效：当前请求带 {@code USER_TOKEN}，SIMS 调用必须收到它。
     *
     * <p>2026-09-23 起标签代理全链路透传用户 token（SIMS 是唯一授权源）；
     * 2026-10-13 起移除服务 token 兜底 —— 无用户 JWT 时严格版直接抛异常，
     * 正常路径（已登录）永远走用户 token。
     */
    private void stubUserToken() {
        when(currentUserTokenResolver.currentToken()).thenReturn(USER_TOKEN);
    }

    // ============ 读端点：URL 层要求已登录（认证在 DIMS），授权在 SIMS ============

    @Test
    void tree_loggedIn_returns200WithNodes() throws Exception {
        stubUserToken();
        stubTree("CUSTOM", true);

        mockMvc.perform(get("/v1/tags/tree").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("root"))
                // 出参键名全部走全局 SNAKE_CASE：node_type / parent_id / is_leaf
                .andExpect(jsonPath("$[0].children[0].node_type").value("CUSTOM"))
                .andExpect(jsonPath("$[0].children[0].id").value("zone"))
                // Lombok boolean + Jackson 属性名推导是个坑，单独锁一下出参键名
                .andExpect(jsonPath("$[0].children[0].children[0].is_leaf").value(true))
                .andExpect(jsonPath("$[0].children[0].children[0].parent_id").value("zone"));
    }

    @Test
    void tree_simsUnreachable_returns502() throws Exception {
        stubUserToken();
        when(simsTagClient.categoryTree(USER_TOKEN)).thenThrow(new SimsRemoteException(502, "SIMS 不可达"));

        mockMvc.perform(get("/v1/tags/tree").with(jwt()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("SIMS_UNAVAILABLE"));
    }

    /**
     * 全链路透传用户 token 后语义反转：SIMS 的 401 = 该用户的 token 在 SIMS 侧无效/过期
     * —— 此时**原样透传 401**，前端 clearToken() 跳登录页是正确行为。
     * （旧模式「服务 token 失效不能踢人」的顾虑已随透传策略消失，见 TagController#fromSims。）
     */
    @Test
    void tree_simsReturns401_isPropagatedAs401() throws Exception {
        stubUserToken();
        when(simsTagClient.categoryTree(USER_TOKEN)).thenThrow(new SimsRemoteException(401, "未登录或登录已过期"));

        mockMvc.perform(get("/v1/tags/tree").with(jwt()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SIMS_UNAUTHORIZED"));
    }

    @Test
    void listTags_returnsPagedItems() throws Exception {
        stubUserToken();
        when(simsTagClient.listTags(USER_TOKEN, 1, 20, null, null, null)).thenReturn(json(
                "{\"items\":[{\"id\":\"t1\",\"name\":\"高速\",\"categoryPath\":\"自定义标签 / 道路\"}],\"total\":1}"));

        mockMvc.perform(get("/v1/tags").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("t1"))
                .andExpect(jsonPath("$.items[0].name").value("高速"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listTags_forwardsPagingAndFilters() throws Exception {
        stubUserToken();
        when(simsTagClient.listTags(USER_TOKEN, 3, 50, "c1", "road", "CUSTOM")).thenReturn(json(
                "{\"items\":[],\"total\":0}"));

        mockMvc.perform(get("/v1/tags").with(jwt())
                        .param("page", "3")
                        .param("pageSize", "50")
                        .param("categoryId", "c1")
                        .param("keyword", "road")
                        .param("nodeType", "CUSTOM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(simsTagClient).listTags(USER_TOKEN, 3, 50, "c1", "road", "CUSTOM");
    }

    /** options 走本地词表缓存（服务 token 领域），但 URL 层仍要求已登录。 */
    @Test
    void options_readsFromCatalogNotSims() throws Exception {
        when(simsTagCatalog.all()).thenReturn(List.of(new TagRef("t1", "高速", "自定义标签 / 道路")));

        mockMvc.perform(get("/v1/tags/options").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t1"))
                .andExpect(jsonPath("$[0].category_path").value("自定义标签 / 道路"));

        verifyNoInteractions(simsTagClient);
    }

    // ==================== 新建标签 ====================

    @Test
    /**
     * 2026-09-23 定案：DIMS 不再持 {@code dims:tag:write} 门禁 —— 即使当前用户只有 DIMS
     * 读权限，请求也照常转发给 SIMS，由它按用户 token 里的 {@code sims:tag:write} 判权
     * （SIMS 是标签权限的唯一授权源）。这里锁「DIMS 侧不做拦截」这一行为。
     */
    void createTag_withoutDimsWriteAuthority_stillProxiesToSims() throws Exception {
        stubUserToken();
        stubTree("CUSTOM", true);
        when(simsTagClient.createTag(USER_TOKEN, "c1", "高速", null)).thenReturn(json(
                "{\"id\":\"t-new\",\"name\":\"高速\"}"));

        mockMvc.perform(post("/v1/tags")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_READ)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category_id\":\"c1\",\"name\":\"高速\"}"))
                .andExpect(status().isCreated());

        verify(simsTagClient).createTag(USER_TOKEN, "c1", "高速", null);
    }

    @Test
    void createTag_onCustomLeaf_returns201AndInvalidatesCatalog() throws Exception {
        stubUserToken();
        stubTree("CUSTOM", true);
        when(simsTagClient.createTag(USER_TOKEN, "c1", "高速", "手动创建")).thenReturn(json(
                "{\"id\":\"t-new\",\"name\":\"高速\",\"categoryId\":\"c1\",\"categoryPath\":\"自定义标签 / 叶子分类\"}"));

        mockMvc.perform(post("/v1/tags")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category_id\":\"c1\",\"name\":\"高速\",\"description\":\"手动创建\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("t-new"))
                .andExpect(jsonPath("$.category_path").value("自定义标签 / 叶子分类"));

        verify(simsTagCatalog).invalidate();
    }

    @Test
    void createTag_onBuiltinLeaf_returns400AndNeverWritesToSims() throws Exception {
        stubUserToken();
        stubTree("BUILTIN", true);

        mockMvc.perform(post("/v1/tags")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category_id\":\"c1\",\"name\":\"高速\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TAG_BUILTIN_READONLY"));

        // 内置区只读由 DIMS 自己判定：绝不能把请求转给 SIMS 再靠它 403 拦
        verify(simsTagClient, never()).createTag(anyString(), anyString(), anyString(), any());
    }

    @Test
    void createTag_onNonLeafCategory_returns400() throws Exception {
        stubUserToken();
        stubTree("CUSTOM", false);

        mockMvc.perform(post("/v1/tags")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category_id\":\"c1\",\"name\":\"高速\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TAG_CATEGORY_NOT_LEAF"));
    }

    @Test
    void createTag_unknownCategory_returns400() throws Exception {
        stubUserToken();
        stubTree("CUSTOM", true);

        mockMvc.perform(post("/v1/tags")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category_id\":\"nope\",\"name\":\"高速\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TAG_CATEGORY_NOT_FOUND"));
    }

    // ==================== 修改标签 ====================

    @Test
    void updateTag_withWriteAuthority_returns200() throws Exception {
        stubUserToken();
        when(simsTagClient.updateTag(USER_TOKEN, "t1", "改名后", null)).thenReturn(json(
                "{\"id\":\"t1\",\"name\":\"改名后\"}"));

        mockMvc.perform(put("/v1/tags/{id}", "t1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名后\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改名后"));

        verify(simsTagCatalog).invalidate();
    }

    @Test
    /** 同上：无 DIMS 写权限也照常转发，判权完全交给 SIMS。 */
    void updateTag_withoutDimsWriteAuthority_stillProxiesToSims() throws Exception {
        stubUserToken();
        when(simsTagClient.updateTag(USER_TOKEN, "t1", "改名后", null)).thenReturn(json(
                "{\"id\":\"t1\",\"name\":\"改名后\"}"));

        mockMvc.perform(put("/v1/tags/{id}", "t1")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名后\"}"))
                .andExpect(status().isOk());

        verify(simsTagClient).updateTag(USER_TOKEN, "t1", "改名后", null);
    }

    // ==================== 分类写 ====================

    @Test
    void createCategory_withoutParentId_returns400() throws Exception {
        stubUserToken();

        mockMvc.perform(post("/v1/tags/categories")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新分组\",\"is_leaf\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TAG_PARENT_REQUIRED"));

        verifyNoInteractions(simsTagClient);
    }

    @Test
    void createCategory_underBuiltinParent_returns400() throws Exception {
        stubUserToken();
        stubTree("BUILTIN", false);

        mockMvc.perform(post("/v1/tags/categories")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parent_id\":\"c1\",\"name\":\"新分组\",\"is_leaf\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TAG_BUILTIN_READONLY"));

        verify(simsTagClient, never()).createCategory(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void createCategory_underCustomParent_returns201() throws Exception {
        stubUserToken();
        when(simsTagClient.categoryTree(USER_TOKEN)).thenReturn(json(
                "[{\"id\":\"zone\",\"name\":\"自定义标签\",\"nodeType\":\"CUSTOM\",\"is_leaf\":false,\"children\":[]}]"));
        when(simsTagClient.createCategory(USER_TOKEN, "zone", "新分组", false)).thenReturn(json(
                "{\"id\":\"c-new\",\"name\":\"新分组\",\"nodeType\":\"CUSTOM\",\"is_leaf\":false}"));

        mockMvc.perform(post("/v1/tags/categories")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parent_id\":\"zone\",\"name\":\"新分组\",\"is_leaf\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("c-new"));

        verify(simsTagCatalog).invalidate();
    }

    @Test
    void renameCategory_onBuiltinNode_returns400() throws Exception {
        stubUserToken();
        stubTree("BUILTIN", false);

        mockMvc.perform(put("/v1/tags/categories/{id}", "zone")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TAG_BUILTIN_READONLY"));

        verify(simsTagClient, never()).renameCategory(anyString(), anyString(), anyString());
    }

    @Test
    void renameCategory_onCustomNode_returns204() throws Exception {
        stubUserToken();
        when(simsTagClient.categoryTree(USER_TOKEN)).thenReturn(json(
                "[{\"id\":\"c1\",\"name\":\"自定义节点\",\"nodeType\":\"CUSTOM\",\"is_leaf\":false,\"children\":[]}]"));

        mockMvc.perform(put("/v1/tags/categories/{id}", "c1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\"}"))
                .andExpect(status().isNoContent());

        verify(simsTagClient).renameCategory(USER_TOKEN, "c1", "新名");
    }

    // ==================== 删除：必须用用户 token ====================

    @Test
    /** 删除同理：DIMS 只要求已登录（URL 层 authenticated），判权完全在 SIMS。 */
    void deleteTag_forwardedToSims_regardlessOfDimsAuthorities() throws Exception {
        stubUserToken();

        mockMvc.perform(delete("/v1/tags/{id}", "t1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.TAG_WRITE))))
                .andExpect(status().isNoContent());

        verify(simsTagClient).removeTag(USER_TOKEN, "t1");
    }

    @Test
    void deleteTag_withDeleteAuthority_passesUserTokenNotServiceToken() throws Exception {
        when(currentUserTokenResolver.currentToken()).thenReturn(USER_TOKEN);

        mockMvc.perform(delete("/v1/tags/{id}", "t1")
                        .with(jwt()
                                .jwt(builder -> builder.tokenValue(USER_TOKEN))
                                .authorities(new SimpleGrantedAuthority(DimsPermission.TAG_DELETE))))
                .andExpect(status().isNoContent());

        // 关键回归：删除用的是用户 token；服务 token 兜底已移除（曾是越权通道），
        // 控制器结构上已无法再触碰服务 token
        verify(simsTagClient).removeTag(USER_TOKEN, "t1");
        verify(simsTagCatalog).invalidate();
    }

    @Test
    void deleteTag_simsForbidden_surfacesActionableHint() throws Exception {
        when(currentUserTokenResolver.currentToken()).thenReturn(USER_TOKEN);
        doThrow(new SimsRemoteException(403, "无权访问该资源"))
                .when(simsTagClient).removeTag(anyString(), eq("t1"));

        mockMvc.perform(delete("/v1/tags/{id}", "t1")
                        .with(jwt()
                                .jwt(builder -> builder.tokenValue(USER_TOKEN))
                                .authorities(new SimpleGrantedAuthority(DimsPermission.TAG_DELETE))))
                .andExpect(status().isForbidden())
                // 不能用默认的 FORBIDDEN：前端对 FORBIDDEN 一律显示「您没有操作权限」，
                // 会把「缺 sims:tag:*」误报成「你在 DIMS 没权限」，排查方向全错。
                // 文案必须指明权限在 SIMS 侧（唯一授权源），让用户/运维去找对地方。
                .andExpect(jsonPath("$.error.code").value("SIMS_FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("SIMS 侧授予对应标签权限")));
    }

    @Test
    void deleteCategory_withDeleteAuthority_usesUserToken() throws Exception {
        stubUserToken();
        when(simsTagClient.categoryTree(USER_TOKEN)).thenReturn(json(
                "[{\"id\":\"c1\",\"name\":\"自定义节点\",\"nodeType\":\"CUSTOM\",\"isLeaf\":false,\"children\":[]}]"));
        when(currentUserTokenResolver.currentToken()).thenReturn(USER_TOKEN);

        mockMvc.perform(delete("/v1/tags/categories/{id}", "c1")
                        .with(jwt()
                                .jwt(builder -> builder.tokenValue(USER_TOKEN))
                                .authorities(new SimpleGrantedAuthority(DimsPermission.TAG_DELETE))))
                .andExpect(status().isNoContent());

        verify(simsTagClient).removeCategory(USER_TOKEN, "c1");
    }

    // ==================== 切片打标（本地关联，保留） ====================

    @Test
    void getSliceTags_anonymous_returnsTagList() throws Exception {
        SliceTagResponse resp = new SliceTagResponse();
        resp.setId("t1");
        resp.setName("高速");
        resp.setType("manual");
        resp.setTimestamp("2026-09-23T05:00:00Z");
        when(tagService.getSliceTags("slice-1")).thenReturn(List.of(resp));

        mockMvc.perform(get("/v1/data-slices/{sliceId}/tags", "slice-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].name").value("高速"));
    }

    @Test
    void setSliceTags_withoutWriteAuthority_returns403() throws Exception {
        mockMvc.perform(put("/v1/data-slices/{sliceId}/tags", "slice-1")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag_ids\":[\"t1\"],\"source\":\"manual\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 2026-09-28 定案：打标写 DIMS 本地 {@code data_slice_tag} 表（不经过 SIMS），
     * 门禁由已退役的 {@code dims:tag:write} 改挂 {@code dims:slice:write}
     * —— 打标本质是「修改切片的关联数据」，与切片上传/导入同源。
     * 持有切片写权限、但没有任何 tag 相关权限的账户应当可以打标。
     */
    @Test
    void setSliceTags_withSliceWriteAuthority_succeeds() throws Exception {
        doNothing().when(tagService).setSliceTags("slice-1", List.of("t1"), "manual");

        mockMvc.perform(put("/v1/data-slices/{sliceId}/tags", "slice-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.SLICE_WRITE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag_ids\":[\"t1\"],\"source\":\"manual\"}"))
                .andExpect(status().isOk());

        verify(tagService).setSliceTags("slice-1", List.of("t1"), "manual");
    }
}
