package com.isim.dims.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.config.CameraEventConfig;
import com.isim.dims.config.PgvectorConfig;
import com.isim.dims.config.StorageConfig;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.GpsTrajectory;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.GpsTrajectoryMapper;
import com.isim.dims.service.impl.LocalStorageService;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.service.impl.VisualizationPipelineServiceImpl;
import com.isim.dims.util.PythonExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisualizationPipelineServiceImplTest {

    @Mock
    private GpsTrajectoryMapper gpsTrajectoryMapper;

    @Mock
    private GpsTrajectoryService gpsTrajectoryService;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private PythonExecutor pythonExecutor;

    @Mock
    private StorageConfig storageConfig;

    @Mock
    private ThreadPoolTaskExecutor videoExtractExecutor;

    @Mock
    private PgvectorConfig pgvectorConfig;

    @Mock
    private CameraEventConfig cameraEventConfig;

    private VisualizationPipelineServiceImpl pipelineService;

    @BeforeEach
    void setUp() {
        lenient().when(storageConfig.getBagDir()).thenReturn("/data/bags");

        // 存储门面：真实 LocalStorageService + StorageAccess（local 模式，trajectories→bagDir）
        LocalStorageService localStorage = new LocalStorageService(storageConfig);
        StorageAccess realAccess = new StorageAccess(localStorage, storageConfig, dataSliceMapper);
        pipelineService = new VisualizationPipelineServiceImpl(gpsTrajectoryMapper,
                gpsTrajectoryService, dataSliceMapper, pythonExecutor, realAccess, videoExtractExecutor,
                pgvectorConfig, cameraEventConfig);
        ReflectionTestUtils.setField(pipelineService, "pythonTimeoutSec", 300);
    }

    // ============ createTask() ============

    @Test
    void createTask_Success_ReturnsTaskId() {
        String taskId = pipelineService.createTask("slice-1", "trajectory");
        assertThat(taskId).isNotNull();

        Map<String, Object> snapshot = pipelineService.getTaskSnapshot(taskId);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.get("task_id")).isEqualTo(taskId);
        assertThat(snapshot.get("slice_id")).isEqualTo("slice-1");
        assertThat(snapshot.get("status")).isEqualTo("PROCESSING");
        assertThat(snapshot.get("progress")).isEqualTo(0);
        assertThat(snapshot.get("extract_type")).isEqualTo("trajectory");
        assertThat(snapshot.get("created_at")).isNotNull();
    }

    // ============ getTaskSnapshot() ============

    @Test
    void getTaskSnapshot_NonExistingTask_ReturnsNull() {
        Map<String, Object> snapshot = pipelineService.getTaskSnapshot("non-existing");
        assertThat(snapshot).isNull();
    }

    // ============ processExtractTrajectory() ============
    //
    // processExtractTrajectory 接受 taskId 参数，不依赖 createTask() 创建内存任务。
    // 当任务不在 taskStore 中时，updateProgress/failTask 为 no-op。
    // 核心业务逻辑（Python 调用、DB 操作）仍可正常测试。

    @Test
    @SuppressWarnings("unchecked")
    void processExtractTrajectory_Success_SavesPoints() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/gnss/bestpos"));

        String pythonResult = "{\"success\":true,\"point_count\":2,\"trajectory\":[" +
                "{\"ts\":\"2026-07-29T10:00:00Z\",\"lat\":31.2,\"lon\":121.4,\"altitude\":10.0,\"heading\":90.0,\"speed\":12.5}," +
                "{\"ts\":\"2026-07-29T10:00:01Z\",\"lat\":31.201,\"lon\":121.401,\"altitude\":10.5,\"heading\":91.0,\"speed\":13.0}" +
                "],\"summary\":{}}";

        when(pythonExecutor.execute(anyString(), anyMap(), anyInt())).thenReturn(pythonResult);
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        pipelineService.processExtractTrajectory("task-1", "slice-1", "/data/bags/slice-1/original.bag", request);

        verify(pythonExecutor).execute(eq("extract_gps_trajectory.py"), anyMap(), eq(300));
        verify(gpsTrajectoryMapper).delete(any(LambdaQueryWrapper.class));
        verify(gpsTrajectoryService).saveBatch(argThat((List<GpsTrajectory> points) -> points.size() == 2));
        // 提取成功有点 → 标记 EXTRACTED
        verify(dataSliceMapper).updateById(argThat((DataSlice s) ->
                "EXTRACTED".equals(s.getTrajectoryStatus()) && s.getTrajectoryCheckedAt() != null));
    }

    @Test
    void processExtractTrajectory_EmptyTrajectory_MarksEmptyAndNoInsert() throws Exception {
        // 防死循环核心：提取成功但 0 点 → 必须持久化 EMPTY 标记，禁止只静默跳过
        ExtractRequest request = new ExtractRequest();

        String pythonResult = "{\"success\":true,\"point_count\":0,\"trajectory\":[]}";

        when(pythonExecutor.execute(anyString(), anyMap(), anyInt())).thenReturn(pythonResult);
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        pipelineService.processExtractTrajectory("task-1", "slice-1", "/data/bags/slice-1/original.bag", request);

        verify(gpsTrajectoryService, never()).saveBatch(anyList());
        // 已确认空标记必须写入 data_slice
        verify(dataSliceMapper).updateById(argThat((DataSlice s) ->
                "EMPTY".equals(s.getTrajectoryStatus()) && s.getTrajectoryCheckedAt() != null));
    }

    @Test
    void processExtractTrajectory_PythonFailure_NoDbOperations() {
        ExtractRequest request = new ExtractRequest();

        when(pythonExecutor.execute(anyString(), anyMap(), anyInt()))
                .thenThrow(new RuntimeException("Timeout: Python script exceeded 300s"));

        pipelineService.processExtractTrajectory("task-1", "slice-1", "/data/bags/slice-1/original.bag", request);

        verify(gpsTrajectoryMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(gpsTrajectoryService, never()).saveBatch(anyList());
    }

    @Test
    void processExtractTrajectory_PythonReturnsFailure_NoBatchInsert() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/gnss/bestpos"));

        String pythonResult = "{\"success\":false,\"error\":\"No GNSS data found in bag\"}";

        when(pythonExecutor.execute(anyString(), anyMap(), anyInt())).thenReturn(pythonResult);

        pipelineService.processExtractTrajectory("task-1", "slice-1", "/data/bags/slice-1/original.bag", request);

        verify(gpsTrajectoryService, never()).saveBatch(anyList());
    }

    @Test
    void processExtractTrajectory_WithTopics_PassesToPython() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/gnss/bestpos", "/sensor/gnss/raw"));

        String pythonResult = "{\"success\":true,\"point_count\":0,\"trajectory\":[]}";

        when(pythonExecutor.execute(anyString(), anyMap(), anyInt())).thenReturn(pythonResult);

        pipelineService.processExtractTrajectory("task-1", "slice-1", "/data/bags/slice-1/original.bag", request);

        // Verify the topics are passed to Python
        verify(pythonExecutor).execute(eq("extract_gps_trajectory.py"),
                argThat((java.util.Map<String, Object> args) -> {
                    List<String> topics = (List<String>) args.get("topics");
                    return topics != null && topics.size() == 2;
                }),
                anyInt());
    }

    // ============ recoverTaskFromDisk() 路径遍历防护 ============

    @Test
    void recoverTaskFromDisk_PathTraversal_Rejected() {
        // 用户可控 taskId 含路径分隔符/相对路径 → 直接拒绝，不访问磁盘
        assertThat(pipelineService.recoverTaskFromDisk("../../../../etc/passwd")).isNull();
        assertThat(pipelineService.recoverTaskFromDisk("..%2F..%2Fetc%2Fpasswd")).isNull();
        assertThat(pipelineService.recoverTaskFromDisk("task-1")).isNull();
        assertThat(pipelineService.recoverTaskFromDisk("")).isNull();
        assertThat(pipelineService.recoverTaskFromDisk(null)).isNull();
    }

    @Test
    void recoverTaskFromDisk_ValidUuidButMissing_ReturnsNull() {
        // 合法 UUID 但文件不存在 → null（不抛异常）
        String validUuid = UUID.randomUUID().toString();
        assertThat(pipelineService.recoverTaskFromDisk(validUuid)).isNull();
    }
}
