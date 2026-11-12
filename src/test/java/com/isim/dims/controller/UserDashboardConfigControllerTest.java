package com.isim.dims.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.UserDashboardConfigDetailResponse;
import com.isim.dims.dto.UserDashboardConfigExportFile;
import com.isim.dims.dto.UserDashboardConfigListResponse;
import com.isim.dims.dto.UserDashboardConfigSummaryResponse;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.UserDashboardConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户个性化配置方案 Controller 测试
 *
 * <p>全接口为 POST + 请求体，路径参数已全部移除（见 Controller 类注释的 BREAKING 说明）。
 * 覆盖 7 个端点：list / create / detail / update / delete / export / import。
 * 断言的响应字段为 snake_case（全局 Jackson 命名策略）。</p>
 *
 * <p><b>身份来源</b>：{@code user_id} 不在请求体里，取自 JWT 的 {@code sub}。
 * 故除「无 token → 401」用例外，所有请求都带
 * {@code .with(jwt().jwt(b -> b.subject(USER_ID)))} 构造的认证上下文；
 * 并专门用「body 里塞他人 user_id」的用例锁定「自报身份被忽略」这一修复点。</p>
 *
 * <p>本切片只装配 Controller 自身 + Web 层，构造依赖必须逐个 {@code @MockBean}
 * （否则启动即报 {@code No qualifying bean of type 'Xxx' is available}）。</p>
 *
 * <p>{@code SliceSecurityTestConfig} 不含 {@code @EnableMethodSecurity}，
 * 故 {@code @RequireDashboardWrite} / {@code @RequireDashboardRead} 在切片里是惰性的——
 * 权限点的生效性由真实 HTTP 联调测试覆盖（{@code tools/api-test/}）。
 * 但 {@code @AuthenticationPrincipal} 仍会从 SecurityContext 解析，故 401 分支可在此覆盖。</p>
 *
 * @feature: replay
 */
@WebMvcTest(UserDashboardConfigController.class)
@Import(SliceSecurityTestConfig.class)
class UserDashboardConfigControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000010";

    /** 冒充用的「他人」用户 ID —— 只出现在请求体里，绝不能影响落库归属 */
    private static final String OTHER_USER_ID = "00000000-0000-0000-0000-0000000000ff";

    private static final String CONFIG_ID = "cfg-1";
    private static final String CREATED_AT = "2026-09-15T06:43:53.000Z";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDashboardConfigService userDashboardConfigService;

    /** 以 {@link #USER_ID} 身份发请求（token 的 sub 即身份来源） */
    private static RequestPostProcessor asUser() {
        return asUser(USER_ID);
    }

    private static RequestPostProcessor asUser(String userId) {
        return jwt().jwt(builder -> builder.subject(userId));
    }

    // ============ POST /v1/user-dashboard-configs/list —— 分页列表 ============

    @Test
    void list_returns200WithItemsTotalPageSize() throws Exception {
        when(userDashboardConfigService.list(eq(USER_ID), eq(1), eq(20))).thenReturn(listResponse());

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":1,\"page_size\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].config_id").value("cfg-1"))
                .andExpect(jsonPath("$.items[0].name").value("方案A"))
                .andExpect(jsonPath("$.items[0].description").value("泊车关注项"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.page_size").value(20));
    }

    @Test
    void list_omittedPagingFields_fallBackToDefaults() throws Exception {
        // 分页字段可为空 → 回落 1 / 20。
        // 这正是 ListRequest 用 Integer 而非 int 的原因：int 缺字段会得 0，
        // page_size 会被误钳制成 1 而不是回落 20。
        when(userDashboardConfigService.list(eq(USER_ID), eq(1), eq(20))).thenReturn(emptyListResponse(1, 20));

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.page_size").value(20));
    }

    @Test
    void list_noRequestBody_fallBackToDefaults() throws Exception {
        // 列表请求的全部字段都可选，故整个 body 都可以省（@RequestBody(required = false)）
        when(userDashboardConfigService.list(eq(USER_ID), eq(1), eq(20))).thenReturn(emptyListResponse(1, 20));

        mockMvc.perform(post("/v1/user-dashboard-configs/list").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.page_size").value(20));
    }

    @Test
    void list_explicitOutOfRangePaging_isClamped() throws Exception {
        // 显式传 0 / 超限 → 钳制到 1 / 100
        when(userDashboardConfigService.list(eq(USER_ID), eq(1), eq(100))).thenReturn(emptyListResponse(1, 100));

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":0,\"page_size\":9999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page_size").value(100));
    }

    @Test
    void list_itemsDoNotCarryConfigJson() throws Exception {
        when(userDashboardConfigService.list(eq(USER_ID), anyInt(), anyInt())).thenReturn(listResponse());

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                // 列表只回摘要：前端须再调 /detail 取配置本体
                .andExpect(jsonPath("$.items[0].config_json").doesNotExist());
    }

    @Test
    void list_emptyResult_returns200WithEmptyItems() throws Exception {
        when(userDashboardConfigService.list(eq(USER_ID), anyInt(), anyInt())).thenReturn(emptyListResponse(1, 20));

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));
    }

    /**
     * 核心回归：请求体里塞他人的 {@code user_id} 不生效 —— 归属只认 token 的 {@code sub}。
     *
     * <p>修复前 {@code user_id} 是请求体字段，Service 会拿它当归属；
     * 修复后该字段已从 DTO 删除，被 Jackson 静默忽略。</p>
     */
    @Test
    void list_spoofedUserIdInBody_isIgnored_usesSubFromToken() throws Exception {
        when(userDashboardConfigService.list(eq(USER_ID), anyInt(), anyInt())).thenReturn(emptyListResponse(1, 20));

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + OTHER_USER_ID + "\"}"))
                .andExpect(status().isOk());

        verify(userDashboardConfigService).list(eq(USER_ID), anyInt(), anyInt());
    }

    @Test
    void list_withoutToken_returns401() throws Exception {
        // 读接口也要求认证：userId 只能从 token 取，匿名时无身份可用
        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void list_tokenWithoutSubject_returns401() throws Exception {
        // sub 缺失的 token 无法确立身份，同样归 401（与 RestAuthenticationEntryPoint 的 code 一致）
        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(jwt().jwt(builder -> builder.subject("")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void list_serviceThrows_returns500WithCode() throws Exception {
        when(userDashboardConfigService.list(any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_LIST_FAILED"));
    }

    @Test
    void list_serviceThrowsInvalid_returns400() throws Exception {
        when(userDashboardConfigService.list(any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("USER_CONFIG_INVALID: userId is required"));

        mockMvc.perform(post("/v1/user-dashboard-configs/list")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_INVALID"));
    }

    // ============ POST /v1/user-dashboard-configs/create —— 新建 ============

    @Test
    void create_valid_returns201AndPassesBody() throws Exception {
        when(userDashboardConfigService.create(eq(USER_ID), any(), any(), any()))
                .thenReturn(detail(CONFIG_ID, "方案A", "泊车关注项"));

        mockMvc.perform(post("/v1/user-dashboard-configs/create")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"方案A\","
                                + "\"description\":\"泊车关注项\",\"config_json\":{\"panels\":[\"obstacles\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.config_id").value(CONFIG_ID))
                .andExpect(jsonPath("$.user_id").value(USER_ID))
                .andExpect(jsonPath("$.name").value("方案A"))
                .andExpect(jsonPath("$.description").value("泊车关注项"))
                .andExpect(jsonPath("$.config_json.panels[0]").value("obstacles"));
    }

    @Test
    void create_passesConfigJsonNodeThrough() throws Exception {
        when(userDashboardConfigService.create(eq(USER_ID), any(), any(), any()))
                .thenReturn(detail(CONFIG_ID, "方案A", null));

        mockMvc.perform(post("/v1/user-dashboard-configs/create")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"方案A\",\"config_json\":{\"panels\":[\"obstacles\",\"lanes\"]}}"))
                .andExpect(status().isCreated());

        // 校验 Controller 把 config_json 原样透传给 Service（名 + 描述 + JsonNode）
        verify(userDashboardConfigService).create(eq(USER_ID), eq("方案A"), any(), any());
    }

    /** 核心回归：写入归属只认 token，body 里的冒充字段被忽略。 */
    @Test
    void create_spoofedUserIdInBody_isIgnored_usesSubFromToken() throws Exception {
        when(userDashboardConfigService.create(eq(USER_ID), any(), any(), any()))
                .thenReturn(detail(CONFIG_ID, "方案A", null));

        mockMvc.perform(post("/v1/user-dashboard-configs/create")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + OTHER_USER_ID + "\",\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isCreated());

        verify(userDashboardConfigService).create(eq(USER_ID), eq("方案A"), any(), any());
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/v1/user-dashboard-configs/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void create_nameConflict_returns409() throws Exception {
        when(userDashboardConfigService.create(eq(USER_ID), any(), any(), any()))
                .thenThrow(new RuntimeException("USER_CONFIG_NAME_CONFLICT: name already exists"));

        mockMvc.perform(post("/v1/user-dashboard-configs/create")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_NAME_CONFLICT"));
    }

    @Test
    void create_invalid_returns400() throws Exception {
        when(userDashboardConfigService.create(eq(USER_ID), any(), any(), any()))
                .thenThrow(new RuntimeException("USER_CONFIG_INVALID: name is required"));

        mockMvc.perform(post("/v1/user-dashboard-configs/create")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_json\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_INVALID"));
    }

    @Test
    void create_serviceThrows_returns500WithCode() throws Exception {
        when(userDashboardConfigService.create(eq(USER_ID), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(post("/v1/user-dashboard-configs/create")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_CREATE_FAILED"));
    }

    // ============ POST /v1/user-dashboard-configs/detail —— 详情 ============

    @Test
    void detail_returns200() throws Exception {
        when(userDashboardConfigService.getDetail(USER_ID, CONFIG_ID))
                .thenReturn(detail(CONFIG_ID, "方案A", "泊车关注项"));

        mockMvc.perform(post("/v1/user-dashboard-configs/detail")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config_id").value(CONFIG_ID))
                .andExpect(jsonPath("$.name").value("方案A"))
                .andExpect(jsonPath("$.config_json.panels[0]").value("obstacles"))
                .andExpect(jsonPath("$.created_at").value(CREATED_AT));
    }

    /** body 里的冒充字段不影响归属：Service 收到的 userId 仍是 token 的 sub。 */
    @Test
    void detail_spoofedUserIdInBody_isIgnored_usesSubFromToken() throws Exception {
        when(userDashboardConfigService.getDetail(USER_ID, CONFIG_ID))
                .thenReturn(detail(CONFIG_ID, "方案A", null));

        mockMvc.perform(post("/v1/user-dashboard-configs/detail")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + OTHER_USER_ID + "\",\"config_id\":\"" + CONFIG_ID + "\"}"))
                .andExpect(status().isOk());

        verify(userDashboardConfigService).getDetail(USER_ID, CONFIG_ID);
    }

    @Test
    void detail_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/v1/user-dashboard-configs/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void detail_notFound_returns404() throws Exception {
        when(userDashboardConfigService.getDetail(USER_ID, CONFIG_ID))
                .thenThrow(new RuntimeException("USER_CONFIG_NOT_FOUND: " + CONFIG_ID));

        mockMvc.perform(post("/v1/user-dashboard-configs/detail")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_NOT_FOUND"));
    }

    @Test
    void detail_serviceThrows_returns500WithCode() throws Exception {
        when(userDashboardConfigService.getDetail(any(), any())).thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(post("/v1/user-dashboard-configs/detail")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_GET_FAILED"));
    }

    // ============ POST /v1/user-dashboard-configs/update —— 编辑 ============

    @Test
    void update_valid_returns200() throws Exception {
        when(userDashboardConfigService.update(eq(USER_ID), eq(CONFIG_ID), any(), any(), any()))
                .thenReturn(detail(CONFIG_ID, "新名", "新描述"));

        mockMvc.perform(post("/v1/user-dashboard-configs/update")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_id\":\"" + CONFIG_ID + "\","
                                + "\"name\":\"新名\",\"description\":\"新描述\",\"config_json\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名"))
                .andExpect(jsonPath("$.description").value("新描述"));
    }

    @Test
    void update_omittedDescription_returns200WithNullDescription() throws Exception {
        // 全量替换语义：请求体里不带 description 即视为清空
        when(userDashboardConfigService.update(eq(USER_ID), eq(CONFIG_ID), any(), any(), any()))
                .thenReturn(detail(CONFIG_ID, "方案A", null));

        mockMvc.perform(post("/v1/user-dashboard-configs/update")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_id\":\"" + CONFIG_ID + "\",\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()));
    }

    /** body 里的冒充字段不影响归属：改他人方案这件事在协议层已不可能。 */
    @Test
    void update_spoofedUserIdInBody_isIgnored_usesSubFromToken() throws Exception {
        when(userDashboardConfigService.update(eq(USER_ID), eq(CONFIG_ID), any(), any(), any()))
                .thenReturn(detail(CONFIG_ID, "方案A", null));

        mockMvc.perform(post("/v1/user-dashboard-configs/update")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + OTHER_USER_ID + "\",\"config_id\":\"" + CONFIG_ID + "\","
                                + "\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isOk());

        verify(userDashboardConfigService).update(eq(USER_ID), eq(CONFIG_ID), any(), any(), any());
    }

    @Test
    void update_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/v1/user-dashboard-configs/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_id\":\"" + CONFIG_ID + "\",\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(userDashboardConfigService.update(eq(USER_ID), eq(CONFIG_ID), any(), any(), any()))
                .thenThrow(new RuntimeException("USER_CONFIG_NOT_FOUND: " + CONFIG_ID));

        mockMvc.perform(post("/v1/user-dashboard-configs/update")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_id\":\"" + CONFIG_ID + "\",\"name\":\"方案A\",\"config_json\":{}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_NOT_FOUND"));
    }

    @Test
    void update_nameConflict_returns409() throws Exception {
        when(userDashboardConfigService.update(eq(USER_ID), eq(CONFIG_ID), any(), any(), any()))
                .thenThrow(new RuntimeException("USER_CONFIG_NAME_CONFLICT: name already exists"));

        mockMvc.perform(post("/v1/user-dashboard-configs/update")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_id\":\"" + CONFIG_ID + "\",\"name\":\"方案B\",\"config_json\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_NAME_CONFLICT"));
    }

    @Test
    void update_invalid_returns400() throws Exception {
        when(userDashboardConfigService.update(eq(USER_ID), eq(CONFIG_ID), any(), any(), any()))
                .thenThrow(new RuntimeException("USER_CONFIG_INVALID: config_json is required"));

        mockMvc.perform(post("/v1/user-dashboard-configs/update")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_id\":\"" + CONFIG_ID + "\",\"name\":\"方案A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_INVALID"));
    }

    // ============ POST /v1/user-dashboard-configs/delete —— 删除 ============

    @Test
    void delete_success_returns204() throws Exception {
        mockMvc.perform(post("/v1/user-dashboard-configs/delete")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isNoContent());

        verify(userDashboardConfigService).delete(USER_ID, CONFIG_ID);
    }

    /** body 里的冒充字段不影响归属：删他人方案这件事在协议层已不可能。 */
    @Test
    void delete_spoofedUserIdInBody_isIgnored_usesSubFromToken() throws Exception {
        mockMvc.perform(post("/v1/user-dashboard-configs/delete")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + OTHER_USER_ID + "\",\"config_id\":\"" + CONFIG_ID + "\"}"))
                .andExpect(status().isNoContent());

        verify(userDashboardConfigService).delete(USER_ID, CONFIG_ID);
    }

    @Test
    void delete_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/v1/user-dashboard-configs/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("USER_CONFIG_NOT_FOUND: " + CONFIG_ID))
                .when(userDashboardConfigService).delete(USER_ID, CONFIG_ID);

        mockMvc.perform(post("/v1/user-dashboard-configs/delete")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_NOT_FOUND"));
    }

    @Test
    void delete_serviceThrows_returns500WithCode() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(userDashboardConfigService).delete(any(), any());

        mockMvc.perform(post("/v1/user-dashboard-configs/delete")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_DELETE_FAILED"));
    }

    // ============ POST /v1/user-dashboard-configs/export —— 导出 ============

    @Test
    void export_returnsJsonAttachmentWithFieldsInSnakeCase() throws Exception {
        when(userDashboardConfigService.export(USER_ID, CONFIG_ID)).thenReturn(exportFile("方案A"));

        mockMvc.perform(post("/v1/user-dashboard-configs/export")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("filename")))
                // 关键：导出内容必须是 snake_case 的 config_json。
                // 若用自建 ObjectMapper 序列化会退化成 configJson，该文件将无法再被导入。
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.exported_at").value("2026-09-17T02:00:00.000Z"))
                .andExpect(jsonPath("$.name").value("方案A"))
                .andExpect(jsonPath("$.description").value("泊车关注项"))
                .andExpect(jsonPath("$.config_json.panels[0]").value("obstacles"));
    }

    @Test
    void export_contentTypeIsJsonUtf8() throws Exception {
        when(userDashboardConfigService.export(USER_ID, CONFIG_ID)).thenReturn(exportFile("方案A"));

        mockMvc.perform(post("/v1/user-dashboard-configs/export")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")));
    }

    @Test
    void export_withoutToken_returns401Json() throws Exception {
        // 导出属下载类操作，已挂 @RequireDashboardRead；无 token 时先被本 Controller 的 401 拦住
        mockMvc.perform(post("/v1/user-dashboard-configs/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void export_notFound_returns404Json() throws Exception {
        when(userDashboardConfigService.export(USER_ID, CONFIG_ID))
                .thenThrow(new RuntimeException("USER_CONFIG_NOT_FOUND: " + CONFIG_ID));

        mockMvc.perform(post("/v1/user-dashboard-configs/export")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_NOT_FOUND"));
    }

    @Test
    void export_serviceThrows_returns500WithCode() throws Exception {
        when(userDashboardConfigService.export(any(), any())).thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(post("/v1/user-dashboard-configs/export")
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(keyBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_EXPORT_FAILED"));
    }

    @Test
    void buildExportFileName_appendsJsonExtension() {
        assertThat(UserDashboardConfigController.buildExportFileName("泊车关注方案"))
                .isEqualTo("泊车关注方案.json");
    }

    @Test
    void buildExportFileName_replacesFileSystemIllegalChars() {
        // /\:*?"<>| 全是文件系统非法字符 → 统一替换为下划线
        assertThat(UserDashboardConfigController.buildExportFileName("a/b\\c:d*e?f\"g<h>i|j"))
                .isEqualTo("a_b_c_d_e_f_g_h_i_j.json");
    }

    @Test
    void buildExportFileName_stripsTrailingDotsAndSpaces() {
        // Windows 会静默吞掉结尾的点与空格，先自行去掉以保证落盘名与方案名一致
        assertThat(UserDashboardConfigController.buildExportFileName("方案A. . "))
                .isEqualTo("方案A.json");
    }

    @Test
    void buildExportFileName_blankName_fallsBackToDefaultStem() {
        assertThat(UserDashboardConfigController.buildExportFileName(null)).isEqualTo("dashboard-config.json");
        assertThat(UserDashboardConfigController.buildExportFileName("   ")).isEqualTo("dashboard-config.json");
        assertThat(UserDashboardConfigController.buildExportFileName("...")).isEqualTo("dashboard-config.json");
    }

    @Test
    void buildExportFileName_overlongName_isTruncated() {
        String longName = "长".repeat(150);
        String fileName = UserDashboardConfigController.buildExportFileName(longName);
        // 100 字符主干 + .json
        assertThat(fileName).hasSize(100 + ".json".length()).endsWith(".json");
    }

    // ============ POST /v1/user-dashboard-configs/import —— 导入 ============

    @Test
    void import_valid_returns201() throws Exception {
        when(userDashboardConfigService.importConfig(eq(USER_ID), eq("泊车关注方案.json"), any(byte[].class)))
                .thenReturn(detail(CONFIG_ID, "泊车关注方案", "泊车关注项"));

        mockMvc.perform(multipart("/v1/user-dashboard-configs/import")
                        .file(exportUpload("泊车关注方案.json"))
                        .with(asUser()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.config_id").value(CONFIG_ID))
                .andExpect(jsonPath("$.name").value("泊车关注方案"))
                .andExpect(jsonPath("$.config_json.panels[0]").value("obstacles"));
    }

    @Test
    void import_passesOriginalFileNameAndRawBytesToService() throws Exception {
        when(userDashboardConfigService.importConfig(eq(USER_ID), anyString(), any(byte[].class)))
                .thenReturn(detail(CONFIG_ID, "泊车关注方案", null));

        mockMvc.perform(multipart("/v1/user-dashboard-configs/import")
                        .file(exportUpload("泊车关注方案.json"))
                        .with(asUser()))
                .andExpect(status().isCreated());

        // 方案名派生在 Service 层，Controller 必须把原始文件名与原始字节透传
        org.mockito.ArgumentCaptor<String> nameCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<byte[]> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(userDashboardConfigService).importConfig(eq(USER_ID), nameCaptor.capture(), bodyCaptor.capture());
        assertThat(nameCaptor.getValue()).isEqualTo("泊车关注方案.json");
        assertThat(new String(bodyCaptor.getValue(), StandardCharsets.UTF_8)).contains("config_json");
    }

    /**
     * 核心回归：multipart 里不再有 {@code user_id} 表单字段，导入归属只认 token。
     *
     * <p>传了也无效 —— 该参数已从方法签名删除，多余的 form 字段不会绑定到任何东西。</p>
     */
    @Test
    void import_spoofedUserIdFormField_isIgnored_usesSubFromToken() throws Exception {
        when(userDashboardConfigService.importConfig(eq(USER_ID), anyString(), any(byte[].class)))
                .thenReturn(detail(CONFIG_ID, "泊车关注方案", null));

        mockMvc.perform(multipart("/v1/user-dashboard-configs/import")
                        .file(exportUpload("泊车关注方案.json"))
                        .param("user_id", OTHER_USER_ID)
                        .with(asUser()))
                .andExpect(status().isCreated());

        verify(userDashboardConfigService).importConfig(eq(USER_ID), anyString(), any(byte[].class));
    }

    @Test
    void import_withoutToken_returns401() throws Exception {
        mockMvc.perform(multipart("/v1/user-dashboard-configs/import")
                        .file(exportUpload("泊车关注方案.json")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void import_nameConflict_returns409() throws Exception {
        when(userDashboardConfigService.importConfig(eq(USER_ID), anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("USER_CONFIG_NAME_CONFLICT: name already exists"));

        mockMvc.perform(multipart("/v1/user-dashboard-configs/import")
                        .file(exportUpload("方案A.json"))
                        .with(asUser()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_NAME_CONFLICT"));
    }

    @Test
    void import_invalidJson_returns400WithImportCode() throws Exception {
        when(userDashboardConfigService.importConfig(eq(USER_ID), anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("USER_CONFIG_IMPORT_INVALID: file is not valid JSON"));

        mockMvc.perform(multipart("/v1/user-dashboard-configs/import")
                        .file(exportUpload("坏文件.json"))
                        .with(asUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_IMPORT_INVALID"));
    }

    @Test
    void import_serviceThrows_returns500WithCode() throws Exception {
        when(userDashboardConfigService.importConfig(eq(USER_ID), anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(multipart("/v1/user-dashboard-configs/import")
                        .file(exportUpload("方案A.json"))
                        .with(asUser()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("USER_CONFIG_IMPORT_FAILED"));
    }

    // ============ 辅助 ============

    private String keyBody() {
        return "{\"config_id\":\"" + CONFIG_ID + "\"}";
    }

    /** 构造一份与真实导出一致的上传文件（含 BOM 无关的纯 UTF-8 JSON） */
    private MockMultipartFile exportUpload(String fileName) {
        String body = "{\"version\":\"1.0\",\"exported_at\":\"2026-09-17T02:00:00.000Z\","
                + "\"name\":\"泊车关注方案\",\"description\":\"泊车关注项\","
                + "\"config_json\":{\"panels\":[\"obstacles\"]}}";
        return new MockMultipartFile("file", fileName, MediaType.APPLICATION_JSON_VALUE,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private UserDashboardConfigExportFile exportFile(String name) {
        UserDashboardConfigExportFile file = new UserDashboardConfigExportFile();
        file.setVersion(UserDashboardConfigExportFile.CURRENT_VERSION);
        file.setExportedAt("2026-09-17T02:00:00.000Z");
        file.setName(name);
        file.setDescription("泊车关注项");
        try {
            file.setConfigJson(JSON.readTree("{\"panels\":[\"obstacles\"]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return file;
    }

    private UserDashboardConfigListResponse listResponse() {
        UserDashboardConfigListResponse resp = new UserDashboardConfigListResponse();
        resp.setItems(List.of(summary("cfg-1", "方案A", "泊车关注项")));
        resp.setTotal(1);
        resp.setPage(1);
        resp.setPageSize(20);
        return resp;
    }

    private UserDashboardConfigListResponse emptyListResponse(int page, int pageSize) {
        UserDashboardConfigListResponse resp = new UserDashboardConfigListResponse();
        resp.setItems(List.of());
        resp.setTotal(0);
        resp.setPage(page);
        resp.setPageSize(pageSize);
        return resp;
    }

    private UserDashboardConfigSummaryResponse summary(String configId, String name, String description) {
        UserDashboardConfigSummaryResponse s = new UserDashboardConfigSummaryResponse();
        s.setConfigId(configId);
        s.setUserId(USER_ID);
        s.setName(name);
        s.setDescription(description);
        s.setCreatedAt(CREATED_AT);
        s.setUpdatedAt(CREATED_AT);
        return s;
    }

    private UserDashboardConfigDetailResponse detail(String configId, String name, String description) {
        UserDashboardConfigDetailResponse d = new UserDashboardConfigDetailResponse();
        d.setConfigId(configId);
        d.setUserId(USER_ID);
        d.setName(name);
        d.setDescription(description);
        d.setCreatedAt(CREATED_AT);
        d.setUpdatedAt(CREATED_AT);
        try {
            d.setConfigJson(JSON.readTree("{\"panels\":[\"obstacles\"]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return d;
    }
}
