package com.isim.dims.controller;

import com.isim.dims.entity.SceneExtractionTask;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DetectedSceneMapper;
import com.isim.dims.mapper.SceneExtractionTaskMapper;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.SceneRuleService;
import com.isim.dims.service.impl.DetectedScenePersistenceService;
import com.isim.dims.service.impl.SceneExtractionPipelineServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.PythonExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SceneExtractionController.readProcessFile 编码变体攻击 MockMvc 层回归（Review S1 补强）
 *
 * <p>在既有 10 个 Service 层用例之上，补 HTTP 层的黑盒攻击变体（local 模式）：
 * <ul>
 *   <li>URL 编码穿越（%2e%2e%2f 解码后的 "../" 及原样编码串）</li>
 *   <li>反斜杠变体（..\..\，Windows 风格路径分隔符 + 编码串）</li>
 *   <li>绝对路径注入（/etc/passwd）</li>
 * </ul>
 * 全部断言 403 FILE_NOT_ALLOWED，且存储层 readString 不得被触达、
 * 响应体不得包含任何文件内容。正常允许列表路径（存量绝对路径产物）必须不受影响。</p>
 *
 * <p>链路：真实 Controller → 真实 SceneExtractionPipelineServiceImpl
 * （allow-list + normalize + ".." 段校验），仅其协作 Bean 以 mock 替代。
 * queryParam 传入的是服务端 URL 解码后的值 —— 等价于真实 %2e%2e%2f 请求解码到达
 * Controller 后的形态；另附原样编码串变体验证编码绕过无效。</p>
 */
@WebMvcTest(SceneExtractionController.class)
@Import({SliceSecurityTestConfig.class, SceneExtractionPipelineServiceImpl.class})
class SceneExtractionSecurityHttpTest {

    /** 存量绝对路径产物（LOCAL 模式兼容路径，允许列表内） */
    private static final String OUTPUT_ABS = "/data/bags/scene_data/slice-1/detected_scenes.json";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SceneExtractionTaskMapper taskMapper;

    @MockBean
    private DetectedSceneMapper sceneMapper;

    @MockBean
    private DataSliceMapper dataSliceMapper;

    @MockBean
    private SceneRuleService sceneRuleService;

    @MockBean
    private PythonExecutor pythonExecutor;

    @MockBean
    private StorageAccess storageAccess;

    @MockBean
    private DetectedScenePersistenceService scenePersistenceService;

    @BeforeEach
    void stubTaskLookup() {
        SceneExtractionTask task = new SceneExtractionTask();
        task.setSliceId("slice-1");
        task.setOutputPath(OUTPUT_ABS);
        task.setIntermediatePaths(List.of("/data/bags/scene_data/slice-1/perception/perception.json"));
        lenient().when(taskMapper.selectOne(any())).thenReturn(task);
    }

    private void assertRejected(String path) throws Exception {
        mockMvc.perform(get("/v1/data-slices/{sliceId}/scene-extraction/files/content", "slice-1")
                        .param("path", path))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FILE_NOT_ALLOWED"))
                // 不得返回文件内容（黑盒断言：/etc/passwd 内容特征不出现）
                .andExpect(content().string(not(containsString("root:"))));
        verify(storageAccess, never()).readString(anyString());
    }

    // ============ URL 编码穿越（%2e%2e%2f → "../"） ============

    @Test
    void readProcessFile_UrlEncodedDotDotTraversal_DecodedForm_Rejected() throws Exception {
        // 真实 %2e%2e%2f 请求经服务端 URL 解码后到达 Controller 的形态
        assertRejected("../etc/passwd");
    }

    @Test
    void readProcessFile_UrlEncodedDotDotTraversal_RawEncodedForm_Rejected() throws Exception {
        // 原样编码串变体（%2e 不被解码/二次解码均不得放行）
        assertRejected("%2e%2e%2fetc%2fpasswd");
    }

    // ============ 反斜杠变体（..\..\） ============

    @Test
    void readProcessFile_BackslashDotDotVariant_Rejected() throws Exception {
        assertRejected("..\\..\\etc\\passwd");
    }

    @Test
    void readProcessFile_BackslashEncodedVariant_Rejected() throws Exception {
        // %5c = 反斜杠的 URL 编码；%2e = 点
        assertRejected("%2e%2e%5c%2e%2e%5cetc%5cpasswd");
    }

    // ============ 绝对路径注入 ============

    @Test
    void readProcessFile_AbsolutePathInjection_Rejected() throws Exception {
        assertRejected("/etc/passwd");
    }

    @Test
    void readProcessFile_AbsolutePathInjection_HomeShadow_Rejected() throws Exception {
        assertRejected("/root/.ssh/id_rsa");
    }

    // ============ 存量正常路径不受影响（回归护栏） ============

    @Test
    void readProcessFile_AllowedAbsoluteOutput_StillServed() throws Exception {
        when(storageAccess.readString(OUTPUT_ABS)).thenReturn("{\"scenes\":[]}");
        mockMvc.perform(get("/v1/data-slices/{sliceId}/scene-extraction/files/content", "slice-1")
                        .param("path", OUTPUT_ABS))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("{\"scenes\":[]}")));
    }

    @Test
    void readProcessFile_AllowedIntermediate_StillServed() throws Exception {
        String intermediate = "/data/bags/scene_data/slice-1/perception/perception.json";
        when(storageAccess.readString(intermediate)).thenReturn("{\"perception\":1}");
        mockMvc.perform(get("/v1/data-slices/{sliceId}/scene-extraction/files/content", "slice-1")
                        .param("path", intermediate))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("{\"perception\":1}")));
    }
}
