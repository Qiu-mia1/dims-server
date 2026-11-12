package com.isim.dims.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.TrajectoryQueryResult;
import com.isim.dims.dto.TrajectoryResponse;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.TrajectoryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TrajectoryController.class)
@Import(SliceSecurityTestConfig.class)
class TrajectoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TrajectoryService trajectoryService;

    // ============ GET /v1/data-slices/{sliceId}/trajectory ============

    @Test
    void getTrajectory_WithData_Returns200() throws Exception {
        TrajectoryResponse response = new TrajectoryResponse();
        response.setSliceId("slice-1");

        TrajectoryResponse.TrajectoryPoint point = new TrajectoryResponse.TrajectoryPoint();
        point.setTs("2026-07-29T10:00:00Z");
        point.setLat(31.2);
        point.setLon(121.4);
        point.setAltitude(10.5);
        point.setHeading(90.0);
        point.setSpeed(12.5);
        response.setTrajectory(List.of(point));

        TrajectoryResponse.TrajectorySummary summary = new TrajectoryResponse.TrajectorySummary();
        summary.setPointCount(1);
        summary.setStartLat(31.2);
        summary.setStartLon(121.4);
        response.setSummary(summary);

        TrajectoryQueryResult result = new TrajectoryQueryResult();
        result.setStatus("READY");
        result.setTrajectory(response);
        when(trajectoryService.getTrajectory("slice-1")).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/trajectory", "slice-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slice_id").value("slice-1"))
                .andExpect(jsonPath("$.trajectory[0].lat").value(31.2))
                .andExpect(jsonPath("$.trajectory[0].lon").value(121.4))
                .andExpect(jsonPath("$.trajectory[0].heading").value(90.0))
                .andExpect(jsonPath("$.summary.point_count").value(1));
    }

    @Test
    void getTrajectory_NoGpsStream_Returns404NoGpsData() throws Exception {
        // 无 GPS sensor_stream → 404 + TRAJECTORY_NO_GPS_DATA（确认为空）
        TrajectoryQueryResult result = new TrajectoryQueryResult();
        result.setStatus("NO_GPS_DATA");
        result.setMessage("切片无 GPS/IMU 传感器流");
        when(trajectoryService.getTrajectory("slice-no-gps")).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/trajectory", "slice-no-gps"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRAJECTORY_NO_GPS_DATA"));
    }

    @Test
    void getTrajectory_HasGpsStreamNotExtracted_Returns202() throws Exception {
        // 有 GPS sensor_stream 但无缓存轨迹，已自动触发提取 → 202 + task_id
        TrajectoryQueryResult result = new TrajectoryQueryResult();
        result.setStatus("NOT_EXTRACTED");
        result.setTaskId("task-9");
        result.setMessage("检测到 GPS 传感器流但无缓存轨迹，已自动触发异步提取");
        when(trajectoryService.getTrajectory("slice-gps-unextracted")).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/trajectory", "slice-gps-unextracted"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("TRAJECTORY_NOT_EXTRACTED"))
                .andExpect(jsonPath("$.task_id").value("task-9"))
                .andExpect(jsonPath("$.slice_id").value("slice-gps-unextracted"));
    }

    @Test
    void getTrajectory_ImportNotReady_Returns409() throws Exception {
        // 导入流水线未完成 → 409 + TRAJECTORY_IMPORT_NOT_READY
        TrajectoryQueryResult result = new TrajectoryQueryResult();
        result.setStatus("IMPORT_NOT_READY");
        result.setMessage("切片导入流水线未完成");
        when(trajectoryService.getTrajectory("slice-parsing")).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/trajectory", "slice-parsing"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRAJECTORY_IMPORT_NOT_READY"));
    }

    @Test
    void getTrajectory_BagFileMissing_Returns409() throws Exception {
        // 有 GPS 流但 bag 文件缺失 → 409 + TRAJECTORY_BAG_FILE_MISSING
        TrajectoryQueryResult result = new TrajectoryQueryResult();
        result.setStatus("BAG_FILE_MISSING");
        result.setMessage("检测到 GPS 传感器流但 bag 文件不存在");
        when(trajectoryService.getTrajectory("slice-bag-missing")).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/trajectory", "slice-bag-missing"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRAJECTORY_BAG_FILE_MISSING"));
    }

    @Test
    void getTrajectory_SliceNotFound_Returns404() throws Exception {
        when(trajectoryService.getTrajectory("invalid-slice"))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: invalid-slice"));

        mockMvc.perform(get("/v1/data-slices/{sliceId}/trajectory", "invalid-slice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ POST /v1/data-slices/{sliceId}/trajectory/extract ============

    @Test
    void extractTrajectory_Returns202() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/gnss/bestpos"));

        when(trajectoryService.extractTrajectory(eq("slice-1"), any(ExtractRequest.class)))
                .thenReturn(Map.of("task_id", "task-1", "status", "PROCESSING"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/trajectory/extract", "slice-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-1"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void extractTrajectory_WithoutBody_Returns202() throws Exception {
        when(trajectoryService.extractTrajectory(eq("slice-1"), any(ExtractRequest.class)))
                .thenReturn(Map.of("task_id", "task-1", "status", "PROCESSING"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/trajectory/extract", "slice-1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-1"));
    }

    @Test
    void extractTrajectory_SliceNotFound_Returns500() throws Exception {
        ExtractRequest request = new ExtractRequest();

        when(trajectoryService.extractTrajectory(eq("invalid"), any(ExtractRequest.class)))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: invalid"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/trajectory/extract", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("TRAJECTORY_EXTRACT_FAILED"));
    }

    // ============ GET /v1/trajectory-tasks/{taskId} ============

    @Test
    void getTrajectoryTask_Found_Returns200() throws Exception {
        Map<String, Object> task = Map.of(
                "task_id", "task-1",
                "slice_id", "slice-1",
                "status", "READY",
                "progress", 100
        );

        when(trajectoryService.getTrajectoryTask("task-1")).thenReturn(task);

        mockMvc.perform(get("/v1/trajectory-tasks/{taskId}", "task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.progress").value(100));
    }

    @Test
    void getTrajectoryTask_NotFound_Returns500() throws Exception {
        when(trajectoryService.getTrajectoryTask("invalid"))
                .thenThrow(new RuntimeException("TRAJECTORY_TASK_NOT_FOUND: invalid"));

        mockMvc.perform(get("/v1/trajectory-tasks/{taskId}", "invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("TRAJECTORY_TASK_FAILED"));
    }
}
