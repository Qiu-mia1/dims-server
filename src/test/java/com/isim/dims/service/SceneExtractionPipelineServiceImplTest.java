package com.isim.dims.service;

import com.isim.dims.entity.SceneExtractionTask;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DetectedSceneMapper;
import com.isim.dims.mapper.SceneExtractionTaskMapper;
import com.isim.dims.service.impl.DetectedScenePersistenceService;
import com.isim.dims.service.impl.SceneExtractionPipelineServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.PythonExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SceneExtractionPipelineServiceImpl.readProcessFile 安全校验测试（Review S1）
 *
 * <p>验证 allow-list 校验层对路径穿越攻击的防御：
 * <ul>
 *   <li>".." 路径段（含跨切片横向）→ 拒绝</li>
 *   <li>绝对路径注入（根目录外）→ 拒绝</li>
 *   <li>存量绝对路径产物（允许列表内）→ 正常访问不受影响</li>
 *   <li>minio 模式相对 key（允许列表内）→ 正常访问不受影响</li>
 * </ul>
 * 存储层第二层防线（LocalStorageService 数据根目录包含性）由 LocalStorageServiceTest 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class SceneExtractionPipelineServiceImplTest {

    /** 存量模式：绝对路径产物（LOCAL 模式兼容路径） */
    private static final String OUTPUT_ABS = "/data/bags/scene_data/slice-1/detected_scenes.json";

    /** minio 模式：相对 key 产物 */
    private static final String OUTPUT_REL = "scene_data/slice-1/detected_scenes.json";

    @Mock
    private SceneExtractionTaskMapper taskMapper;

    @Mock
    private DetectedSceneMapper sceneMapper;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private SceneRuleService sceneRuleService;

    @Mock
    private PythonExecutor pythonExecutor;

    @Mock
    private StorageAccess storageAccess;

    @Mock
    private DetectedScenePersistenceService scenePersistenceService;

    @InjectMocks
    private SceneExtractionPipelineServiceImpl pipelineService;

    private SceneExtractionTask taskWithOutput(String outputPath, List<String> intermediates) {
        SceneExtractionTask task = new SceneExtractionTask();
        task.setSliceId("slice-1");
        task.setOutputPath(outputPath);
        task.setIntermediatePaths(intermediates);
        return task;
    }

    @BeforeEach
    void stubTaskLookup() {
        // 默认：output_path 为存量绝对路径；个别用例在测试体内重新 stub（需 lenient）
        lenient().when(taskMapper.selectOne(any()))
                .thenReturn(taskWithOutput(OUTPUT_ABS, List.of()));
    }

    private void assertRejected(String path) {
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> pipelineService.readProcessFile("slice-1", path));
        assertThat(exception.getMessage()).startsWith("FILE_NOT_ALLOWED");
        verify(storageAccess, never()).readString(anyString());
    }

    // ============ 路径穿越防御（Review S1） ============

    @Test
    void readProcessFile_DotDotTraversal_Rejected() {
        // 字符串前缀命中 output_path 但 "../.." 归一化后逃逸到 /etc/passwd → 拒绝
        assertRejected(OUTPUT_ABS + "/../../../../etc/passwd");
    }

    @Test
    void readProcessFile_AbsolutePathInjection_Rejected() {
        // 直接注入根目录外绝对路径 → 拒绝
        assertRejected("/etc/passwd");
    }

    @Test
    void readProcessFile_CrossSliceTraversal_Rejected() {
        // 横向读取其他切片产物（本切片 output 前缀 + ".." 跨到 slice-2）→ 拒绝
        assertRejected("/data/bags/scene_data/slice-1/../slice-2/detected_scenes.json");
    }

    @Test
    void readProcessFile_RelativeKeyWithDotDot_Rejected() {
        when(taskMapper.selectOne(any()))
                .thenReturn(taskWithOutput(OUTPUT_REL, List.of()));
        assertRejected("scene_data/slice-1/../../../../etc/passwd");
    }

    @Test
    void readProcessFile_NullOrBlankPath_Rejected() {
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
    }

    // ============ 存量/正常路径不受影响 ============

    @Test
    void readProcessFile_LegacyAbsoluteOutputPath_Allowed() {
        when(storageAccess.readString(OUTPUT_ABS)).thenReturn("{}");
        String content = pipelineService.readProcessFile("slice-1", OUTPUT_ABS);
        assertThat(content).isEqualTo("{}");
        verify(storageAccess).readString(OUTPUT_ABS);
    }

    @Test
    void readProcessFile_LegacyAbsoluteChild_Allowed() {
        // output_path 同目录下的后续产物（openscenario/*.xosc）
        String child = "/data/bags/scene_data/slice-1/openscenario/cut.xosc";
        when(storageAccess.readString(child)).thenReturn("<openscenario/>");
        String content = pipelineService.readProcessFile("slice-1", child);
        assertThat(content).isEqualTo("<openscenario/>");
        verify(storageAccess).readString(child);
    }

    @Test
    void readProcessFile_RelativeKeyChild_Allowed() {
        when(taskMapper.selectOne(any()))
                .thenReturn(taskWithOutput(OUTPUT_REL, List.of()));
        String child = "scene_data/slice-1/openscenario/cut.xosc";
        when(storageAccess.readString(child)).thenReturn("<openscenario/>");
        String content = pipelineService.readProcessFile("slice-1", child);
        assertThat(content).isEqualTo("<openscenario/>");
        verify(storageAccess).readString(child);
    }

    @Test
    void readProcessFile_IntermediatePath_Allowed() {
        String intermediate = "/data/bags/scene_data/slice-1/perception/perception.json";
        when(taskMapper.selectOne(any()))
                .thenReturn(taskWithOutput(OUTPUT_ABS, List.of(intermediate)));
        when(storageAccess.readString(intermediate)).thenReturn("{}");
        String content = pipelineService.readProcessFile("slice-1", intermediate);
        assertThat(content).isEqualTo("{}");
        verify(storageAccess).readString(intermediate);
    }

    @Test
    void readProcessFile_NotInAllowList_Rejected() {
        // 允许列表外路径（同根目录但非本切片产物）→ 拒绝
        assertRejected("/data/bags/scene_data/other-slice/detected_scenes.json");
    }
}
