package com.isim.dims.security;

import com.isim.dims.config.SecurityConfig;
import com.isim.dims.controller.DataSliceController;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.BosDirectoryRestoreService;
import com.isim.dims.service.DataSliceService;
import com.isim.dims.service.FisheyePreviewService;
import com.isim.dims.service.LocalDirectoryImportService;
import com.isim.dims.service.SliceLoadService;
import com.isim.dims.service.VideoSemanticSearchService;
import com.isim.dims.service.impl.FilterOptionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 切片删除类权限回归测试（加载真实 SecurityConfig，方法级 @PreAuthorize 生效）
 *
 * <p>核心断言：<b>「能上传」不再等于「能删」</b>。</p>
 * <ul>
 *   <li>{@code DELETE /v1/data-slices/{sliceId}} → 只认 {@code dims:slice:delete}，
 *       仅有 {@code dims:slice:write} 一律 403；</li>
 *   <li>{@code POST /v1/data-slices/reimport} → 只认 {@code dims:slice:reimport}
 *       （该接口实现含物理删除原切片，故不归 write，避免绕过删除限制）；</li>
 *   <li>无 token → 401。</li>
 * </ul>
 */
@WebMvcTest(controllers = DataSliceController.class)
@Import(SecurityConfig.class)
class SliceDeletePermissionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSliceService dataSliceService;

    @MockBean
    private LocalDirectoryImportService localDirectoryImportService;

    @MockBean
    private DataSliceMapper dataSliceMapper;

    /** 触发类型下拉选项提供者（本类只关心删除类权限，mock 掉即可让切片上下文起来） */
    @MockBean
    private FilterOptionProvider filterOptionProvider;

    /** 同上：@WebMvcTest 切片不扫自动配置，缺这个 bean 会让 dataSliceController 装配失败 */
    @MockBean
    private BosDirectoryRestoreService bosDirectoryRestoreService;

    /** 同上 */
    @MockBean
    private SliceLoadService sliceLoadService;

    /** 同上 */
    @MockBean
    private FisheyePreviewService fisheyePreviewService;

    /** 同上 */
    @MockBean
    private VideoSemanticSearchService videoSemanticSearchService;

    private static final String REIMPORT_BODY = "{\"slice_ids\": [\"s1\"]}";

    // ============ DELETE /v1/data-slices/{sliceId} ============

    @Test
    void deleteSlice_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(delete("/v1/data-slices/s1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 关键回归：仅有上传/导入权限（{@code dims:slice:write}）不能删除切片。
     * 细化前该请求会 204 通过——普通账户因此可以直接删数据。
     */
    @Test
    void deleteSlice_WithSliceWriteOnly_Returns403() throws Exception {
        mockMvc.perform(delete("/v1/data-slices/s1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.SLICE_WRITE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteSlice_WithSliceDelete_Returns204() throws Exception {
        mockMvc.perform(delete("/v1/data-slices/s1")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.SLICE_DELETE))))
                .andExpect(status().isNoContent());
    }

    // ============ POST /v1/data-slices/reimport ============

    @Test
    void reimport_WithoutToken_Returns401() throws Exception {
        mockMvc.perform(post("/v1/data-slices/reimport")
                        .contentType("application/json")
                        .content(REIMPORT_BODY))
                .andExpect(status().isUnauthorized());
    }

    /** 关键回归：reimport 内部执行物理删除，仅有 write 权限一律拒绝。 */
    @Test
    void reimport_WithSliceWriteOnly_Returns403() throws Exception {
        mockMvc.perform(post("/v1/data-slices/reimport")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.SLICE_WRITE)))
                        .contentType("application/json")
                        .content(REIMPORT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void reimport_WithSliceReimport_Returns202() throws Exception {
        // dataSliceMapper.selectById 未被 stub → 返回 null → 控制器 continue → 逐项结果为空 → 202
        mockMvc.perform(post("/v1/data-slices/reimport")
                        .with(jwt().authorities(new SimpleGrantedAuthority(DimsPermission.SLICE_REIMPORT)))
                        .contentType("application/json")
                        .content(REIMPORT_BODY))
                .andExpect(status().isAccepted());
    }
}
