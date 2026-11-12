package com.isim.dims.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.config.StorageConfig;
import com.isim.dims.dto.TrajectoryQueryResult;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.GpsTrajectory;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.GpsTrajectoryMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.impl.LocalStorageService;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.service.impl.TrajectoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TrajectoryServiceImpl 单元测试
 *
 * 重点覆盖 Review 发现的三态判定与"已确认空"标记防死循环逻辑：
 * 1. 有缓存轨迹 → READY
 * 2. trajectory_status=EMPTY → NO_GPS_DATA 且不再触发提取（防死循环）
 * 3. 无 GPS sensor_stream → NO_GPS_DATA
 * 4. 有 GPS 流但未提取 → 自动触发异步提取 → NOT_EXTRACTED
 */
@ExtendWith(MockitoExtension.class)
class TrajectoryServiceImplTest {

    @Mock
    private GpsTrajectoryMapper gpsTrajectoryMapper;
    @Mock
    private DataSliceMapper dataSliceMapper;
    @Mock
    private SensorStreamMapper sensorStreamMapper;
    @Mock
    private IngestionTaskMapper ingestionTaskMapper;
    @Mock
    private StorageConfig storageConfig;
    @Mock
    private VisualizationPipelineService visualizationPipelineService;

    private TrajectoryServiceImpl trajectoryService;

    @TempDir
    Path tempDir;

    private DataSlice slice;

    @BeforeEach
    void setUp() {
        // 存储门面：真实 LocalStorageService + StorageAccess（local 模式，bagDir→tempDir）
        LocalStorageService localStorage = new LocalStorageService(storageConfig);
        StorageAccess realAccess = new StorageAccess(localStorage, storageConfig, dataSliceMapper);
        trajectoryService = new TrajectoryServiceImpl(gpsTrajectoryMapper, dataSliceMapper,
                sensorStreamMapper, ingestionTaskMapper, realAccess, visualizationPipelineService);

        slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("READY");
        // S1 根目录包含性：bagDir 指向 tempDir（存量绝对路径与相对 key 均在其内）
        lenient().when(storageConfig.getBagDir()).thenReturn(tempDir.toString());
        // lenient：共享 stub 被部分测试覆盖或未使用（strict 模式下会报 UnnecessaryStubbing）
        lenient().when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        lenient().when(gpsTrajectoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    // ============ 一级：有缓存轨迹 → READY ============

    @Test
    void getTrajectory_HasCachedPoints_ReturnsReady() {
        GpsTrajectory pt = new GpsTrajectory();
        pt.setSliceId("slice-1");
        pt.setTs(LocalDateTime.now(ZoneOffset.UTC));
        pt.setLat(31.2);
        pt.setLon(121.4);
        when(gpsTrajectoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pt));

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-1");

        assertThat(result.getStatus()).isEqualTo("READY");
        assertThat(result.getTrajectory()).isNotNull();
        // 缓存命中不应触发任何提取
        verify(visualizationPipelineService, never()).createTask(anyString(), anyString());
    }

    // ============ 二级：EMPTY 标记 → NO_GPS_DATA，不再触发提取（防死循环） ============

    @Test
    void getTrajectory_EmptyMarker_ReturnsNoGpsDataWithoutRetrigger() {
        // 提取执行过但结果为 0 点（如 odometry/heading 被归为 gnss_imu 但无实际 GNSS 数据）
        slice.setTrajectoryStatus("EMPTY");
        slice.setTrajectoryCheckedAt(LocalDateTime.now(ZoneOffset.UTC));

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-1");

        assertThat(result.getStatus()).isEqualTo("NO_GPS_DATA");
        // 防死循环核心断言：不查 sensor_stream、不创建任务、不触发提取
        verify(sensorStreamMapper, never()).countGpsStreams(anyString());
        verify(visualizationPipelineService, never()).createTask(anyString(), anyString());
        verify(visualizationPipelineService, never()).processExtractTrajectory(anyString(), anyString(),
                anyString(), any());
    }

    // ============ 二级：无 GPS sensor_stream → NO_GPS_DATA ============

    @Test
    void getTrajectory_NoGpsStream_ReturnsNoGpsData() {
        when(sensorStreamMapper.countGpsStreams("slice-1")).thenReturn(0L);
        IngestionTask task = new IngestionTask();
        task.setStatus("READY");
        when(ingestionTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-1");

        assertThat(result.getStatus()).isEqualTo("NO_GPS_DATA");
        verify(visualizationPipelineService, never()).createTask(anyString(), anyString());
    }

    @Test
    void getTrajectory_ImportNotReady_ReturnsImportNotReady() {
        when(sensorStreamMapper.countGpsStreams("slice-1")).thenReturn(0L);
        IngestionTask task = new IngestionTask();
        task.setStatus("PARSING"); // 导入未完成，不能下"无 GPS"结论
        when(ingestionTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-1");

        assertThat(result.getStatus()).isEqualTo("IMPORT_NOT_READY");
    }

    // ============ 三级：有 GPS 流但未提取 → 自动触发提取 ============

    @Test
    void getTrajectory_HasGpsStreamNotExtracted_AutoTriggersExtraction() throws Exception {
        // 构造真实存在的 bag 文件（resolveBagFilePath 校验磁盘存在性）
        Path sliceDir = tempDir.resolve("slice-1");
        Files.createDirectories(sliceDir);
        Path bagFile = sliceDir.resolve("original.bag");
        Files.createFile(bagFile);
        // file_path 已指向存在的文件 → resolveBagFilePath 直接命中，不触碰 bagDir 候选路径
        slice.setFilePath(bagFile.toString());

        when(sensorStreamMapper.countGpsStreams("slice-1")).thenReturn(1L);
        when(visualizationPipelineService.findActiveTaskBySlice("slice-1", "trajectory")).thenReturn(null);
        when(visualizationPipelineService.createTask("slice-1", "trajectory")).thenReturn("task-1");

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-1");

        assertThat(result.getStatus()).isEqualTo("NOT_EXTRACTED");
        assertThat(result.getTaskId()).isEqualTo("task-1");
        verify(visualizationPipelineService).createTask("slice-1", "trajectory");
        verify(visualizationPipelineService).processExtractTrajectory(eq("task-1"), eq("slice-1"),
                eq(bagFile.toString()), any());
    }

    @Test
    void getTrajectory_HasGpsStreamWithActiveTask_ReusesTask() throws Exception {
        Path sliceDir = tempDir.resolve("slice-1");
        Files.createDirectories(sliceDir);
        Path bagFile = sliceDir.resolve("original.bag");
        Files.createFile(bagFile);
        // file_path 已指向存在的文件 → resolveBagFilePath 直接命中，不触碰 bagDir 候选路径
        slice.setFilePath(bagFile.toString());

        when(sensorStreamMapper.countGpsStreams("slice-1")).thenReturn(1L);
        // 已有进行中的任务 → 复用，不重复创建（防并发重复提取）
        when(visualizationPipelineService.findActiveTaskBySlice("slice-1", "trajectory")).thenReturn("task-active");

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-1");

        assertThat(result.getStatus()).isEqualTo("NOT_EXTRACTED");
        assertThat(result.getTaskId()).isEqualTo("task-active");
        verify(visualizationPipelineService, never()).createTask(anyString(), anyString());
        verify(visualizationPipelineService, never()).processExtractTrajectory(anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void getTrajectory_HasGpsStreamBagMissing_ReturnsBagFileMissing() {
        // 有 GPS 流但 file_path 失效且无候选文件 → 快速失败，不创建注定失败的任务
        slice.setFilePath(tempDir.resolve("nonexistent.bag").toString());
        when(storageConfig.getBagDir()).thenReturn(tempDir.toString());
        when(sensorStreamMapper.countGpsStreams("slice-1")).thenReturn(1L);

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-1");

        assertThat(result.getStatus()).isEqualTo("BAG_FILE_MISSING");
        verify(visualizationPipelineService, never()).createTask(anyString(), anyString());
    }

    @Test
    void getTrajectory_StalePathWithValidCandidate_RepairsFilePath() throws Exception {
        // file_path 指向已删除文件（CFDI 历史数据），但 {bagDir}/{sliceId}/original.bag 存在 → 自愈并触发提取
        Path sliceDir = tempDir.resolve("slice-2");
        Files.createDirectories(sliceDir);
        Path bagFile = sliceDir.resolve("original.bag");
        Files.createFile(bagFile);
        slice.setId("slice-2");
        slice.setFilePath(tempDir.resolve("slice-2/original.tar.gz").toString());
        when(dataSliceMapper.selectById("slice-2")).thenReturn(slice);
        when(gpsTrajectoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(storageConfig.getBagDir()).thenReturn(tempDir.toString());
        when(sensorStreamMapper.countGpsStreams("slice-2")).thenReturn(1L);
        when(visualizationPipelineService.createTask("slice-2", "trajectory")).thenReturn("task-2");

        TrajectoryQueryResult result = trajectoryService.getTrajectory("slice-2");

        assertThat(result.getStatus()).isEqualTo("NOT_EXTRACTED");
        // file_path 已被自愈为规范相对 key（slices/{id}/original.bag，语义升级为存储引用）
        verify(dataSliceMapper).updateById(argThat((DataSlice s) ->
                "slices/slice-2/original.bag".equals(s.getFilePath())));
    }
}
