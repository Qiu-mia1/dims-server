package com.isim.dims.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.config.StorageConfig;
import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.entity.ReplayExtractionTask;
import com.isim.dims.entity.SensorCalibration;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ReplayExtractionTaskMapper;
import com.isim.dims.mapper.SensorCalibrationMapper;
import com.isim.dims.service.impl.LocalStorageService;
import com.isim.dims.service.impl.ReplayExtractionPipelineServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.PythonExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回放提取流水线测试（标定导出格式防回归）
 *
 * <p>验证：有标定时 processExtraction（obstacles）传给 extract_obstacles.py 的
 * args 含 calibration_path，且该文件结构符合脚本契约
 * { frame: "ego", unit: "m", sensors: [{ sensor_name, sensor_type, x, y, z, yaw, pitch, roll }] }；
 * 任务终态回写 SUCCESS + output_path。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReplayExtractionPipelineServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    private ReplayExtractionTaskMapper taskMapper;

    @Mock
    private SensorCalibrationMapper sensorCalibrationMapper;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private PythonExecutor pythonExecutor;

    @Mock
    private StorageConfig storageConfig;

    private ReplayExtractionPipelineServiceImpl pipelineService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(storageConfig.getReplayCacheDir()).thenReturn(tempDir.toString());
        // S1 根目录包含性校验：测试 bag 路径 /data/bags/... 需位于配置根目录内
        lenient().when(storageConfig.getBagDir()).thenReturn("/data/bags");

        // 存储门面：真实 LocalStorageService + StorageAccess（local 模式，replay→replayCacheDir）
        LocalStorageService localStorage = new LocalStorageService(storageConfig);
        StorageAccess realAccess = new StorageAccess(localStorage, storageConfig, null);
        pipelineService = new ReplayExtractionPipelineServiceImpl(taskMapper,
                sensorCalibrationMapper, dataSliceMapper, pythonExecutor, realAccess);
    }

    @Test
    void obstacles_WithCalibration_ExportsCalibrationJson() throws Exception {
        // PENDING 任务 → 流水线可抢占进入 RUNNING
        ReplayExtractionTask task = new ReplayExtractionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setType("obstacles");
        task.setTopics("[\"/perception/radar/result_front\"]");
        task.setStartTs(LocalDateTime.of(2026, 7, 24, 10, 0, 0));
        task.setEndTs(LocalDateTime.of(2026, 7, 24, 10, 0, 30));
        task.setStatus("PENDING");
        when(taskMapper.selectById("task-1")).thenReturn(task);

        // 一条标定记录（雷达 result_front，pose_json 结构同契约）
        SensorCalibration calib = new SensorCalibration();
        calib.setId("calib-1");
        calib.setSliceId("slice-1");
        calib.setSensorName("result_front");
        calib.setSensorType("radar");
        calib.setPoseJson("{\"x\":0.1,\"y\":0.0,\"z\":0.5,\"yaw\":0.02,\"pitch\":0.0,\"roll\":0.0}");
        when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of(calib));

        // 脚本成功 + 产物 index.json 存在（脚本契约产物，流水线有校验）
        Path obstaclesDir = tempDir.resolve("slice-1/replay/obstacles");
        Files.createDirectories(obstaclesDir);
        Files.writeString(obstaclesDir.resolve("index.json"), "{\"calibrated\":true,\"segments\":[]}");
        when(pythonExecutor.execute(eq("extract_obstacles.py"), any(), anyInt()))
                .thenReturn("{\"success\": true, \"segments\": 0, \"obstacle_count\": 0, \"calibrated\": true}");

        pipelineService.processExtraction(task, "/data/bags/slice-1/original.bag", new ReplayExtractionRequest());

        // 1. 传给脚本的 args 含 calibration_path
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonExecutor).execute(eq("extract_obstacles.py"), argsCaptor.capture(), anyInt());
        String calibrationPath = (String) argsCaptor.getValue().get("calibration_path");
        assertThat(calibrationPath).isNotNull();

        // 2. calibration.json 结构断言（脚本契约）
        JsonNode root = objectMapper.readTree(Files.readString(Path.of(calibrationPath)));
        assertThat(root.path("frame").asText()).isEqualTo("ego");
        assertThat(root.path("unit").asText()).isEqualTo("m");
        assertThat(root.path("sensors")).hasSize(1);
        JsonNode sensor = root.path("sensors").get(0);
        assertThat(sensor.path("sensor_name").asText()).isEqualTo("result_front");
        assertThat(sensor.path("sensor_type").asText()).isEqualTo("radar");
        assertThat(sensor.path("x").asDouble()).isEqualTo(0.1);
        assertThat(sensor.path("z").asDouble()).isEqualTo(0.5);
        assertThat(sensor.path("yaw").asDouble()).isEqualTo(0.02); // unit: rad
        assertThat(sensor.path("roll").asDouble()).isEqualTo(0.0);

        // 3. 任务终态 SUCCESS + 产物路径入库（两次回写：RUNNING → 终态，断言最后一次）
        ArgumentCaptor<ReplayExtractionTask> taskCaptor =
                ArgumentCaptor.forClass(ReplayExtractionTask.class);
        verify(taskMapper, times(2)).updateById(taskCaptor.capture());
        ReplayExtractionTask finalTask = taskCaptor.getAllValues().get(1);
        assertThat(finalTask.getStatus()).isEqualTo("SUCCESS");
        assertThat(finalTask.getProgress()).isEqualTo(100);
        assertThat(finalTask.getOutputPath()).isEqualTo(obstaclesDir.toString());
    }

    @Test
    void obstacles_WithoutCalibration_OmitsCalibrationPath() throws Exception {
        ReplayExtractionTask task = new ReplayExtractionTask();
        task.setId("task-2");
        task.setSliceId("slice-1");
        task.setType("obstacles");
        task.setTopics("[\"/perception/radar/result_front\"]");
        task.setStartTs(LocalDateTime.of(2026, 7, 24, 10, 0, 0));
        task.setEndTs(LocalDateTime.of(2026, 7, 24, 10, 0, 30));
        task.setStatus("PENDING");
        when(taskMapper.selectById("task-2")).thenReturn(task);

        // 无标定
        when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of());

        Path obstaclesDir = tempDir.resolve("slice-1/replay/obstacles");
        Files.createDirectories(obstaclesDir);
        Files.writeString(obstaclesDir.resolve("index.json"), "{\"calibrated\":false,\"segments\":[]}");
        when(pythonExecutor.execute(eq("extract_obstacles.py"), any(), anyInt()))
                .thenReturn("{\"success\": true, \"segments\": 0, \"obstacle_count\": 0, \"calibrated\": false}");

        pipelineService.processExtraction(task, "/data/bags/slice-1/original.bag", new ReplayExtractionRequest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonExecutor).execute(eq("extract_obstacles.py"), argsCaptor.capture(), anyInt());
        assertThat(argsCaptor.getValue()).doesNotContainKey("calibration_path");

        ArgumentCaptor<ReplayExtractionTask> taskCaptor =
                ArgumentCaptor.forClass(ReplayExtractionTask.class);
        verify(taskMapper, times(2)).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues().get(1).getStatus()).isEqualTo("SUCCESS");
    }

    // ============ v1.4 新增 type=perception（smooth_object 融合感知） ============

    @Test
    void perception_CallsExtractPerceptionScript() throws Exception {
        ReplayExtractionTask task = new ReplayExtractionTask();
        task.setId("task-3");
        task.setSliceId("slice-1");
        task.setType("perception");
        task.setTopics("[\"/momenta_public/perception/fusion/smooth_object\"]");
        task.setStartTs(LocalDateTime.of(2026, 7, 24, 10, 0, 0));
        task.setEndTs(LocalDateTime.of(2026, 7, 24, 10, 0, 30));
        task.setStatus("PENDING");
        when(taskMapper.selectById("task-3")).thenReturn(task);
        lenient().when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of());

        Path perceptionDir = tempDir.resolve("slice-1/replay/perception");
        Files.createDirectories(perceptionDir);
        Files.writeString(perceptionDir.resolve("index.json"),
                "{\"frame\":\"global\",\"unit\":\"m\",\"segments\":[]}");
        when(pythonExecutor.execute(eq("extract_perception.py"), any(), anyInt()))
                .thenReturn("{\"success\": true, \"segments\": 0, \"frames\": 0, \"object_count\": 0, \"frame\": \"global\"}");

        pipelineService.processExtraction(task, "/data/bags/slice-1/original.bag", new ReplayExtractionRequest());

        // 1. 调用 extract_perception.py，args 含 output_dir + bag 时间窗口
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonExecutor).execute(eq("extract_perception.py"), argsCaptor.capture(), anyInt());
        Map<String, Object> args = argsCaptor.getValue();
        assertThat(args).containsEntry("bag_path", "/data/bags/slice-1/original.bag");
        assertThat(args.get("output_dir")).isEqualTo(perceptionDir.toString());
        assertThat((List<String>) args.get("topics"))
                .containsExactly("/momenta_public/perception/fusion/smooth_object");

        // 2. 任务终态 SUCCESS + 产物路径入库
        ArgumentCaptor<ReplayExtractionTask> taskCaptor =
                ArgumentCaptor.forClass(ReplayExtractionTask.class);
        verify(taskMapper, times(2)).updateById(taskCaptor.capture());
        ReplayExtractionTask finalTask = taskCaptor.getAllValues().get(1);
        assertThat(finalTask.getStatus()).isEqualTo("SUCCESS");
        assertThat(finalTask.getOutputPath()).isEqualTo(perceptionDir.toString());
    }

    // ============ v1.4 新增 type=map（ddld landmark_vis 地图） ============

    @Test
    void map_CallsExtractMapScript() throws Exception {
        ReplayExtractionTask task = new ReplayExtractionTask();
        task.setId("task-4");
        task.setSliceId("slice-1");
        task.setType("map");
        task.setTopics("[\"/momenta_public/ddld/landmark_vis\"]");
        task.setStartTs(LocalDateTime.of(2026, 7, 24, 10, 0, 0));
        task.setEndTs(LocalDateTime.of(2026, 7, 24, 10, 0, 30));
        task.setStatus("PENDING");
        when(taskMapper.selectById("task-4")).thenReturn(task);
        lenient().when(sensorCalibrationMapper.selectList(any())).thenReturn(List.of());

        Path mapDir = tempDir.resolve("slice-1/replay/map");
        Files.createDirectories(mapDir);
        Files.writeString(mapDir.resolve("index.json"),
                "{\"frame\":\"ego\",\"unit\":\"m\",\"segments\":[]}");
        when(pythonExecutor.execute(eq("extract_map.py"), any(), anyInt()))
                .thenReturn("{\"success\": true, \"segments\": 0, \"frames\": 0, \"line_count\": 0, \"frame\": \"ego\"}");

        pipelineService.processExtraction(task, "/data/bags/slice-1/original.bag", new ReplayExtractionRequest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonExecutor).execute(eq("extract_map.py"), argsCaptor.capture(), anyInt());
        Map<String, Object> args = argsCaptor.getValue();
        assertThat(args.get("output_dir")).isEqualTo(mapDir.toString());
        assertThat((List<String>) args.get("topics"))
                .containsExactly("/momenta_public/ddld/landmark_vis");

        ArgumentCaptor<ReplayExtractionTask> taskCaptor =
                ArgumentCaptor.forClass(ReplayExtractionTask.class);
        verify(taskMapper, times(2)).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues().get(1).getStatus()).isEqualTo("SUCCESS");
        assertThat(taskCaptor.getAllValues().get(1).getOutputPath()).isEqualTo(mapDir.toString());
    }
}
