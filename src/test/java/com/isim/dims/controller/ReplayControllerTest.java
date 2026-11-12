package com.isim.dims.controller;

import com.isim.dims.dto.MapFrameResponse;
import com.isim.dims.dto.ObstacleFrameResponse;
import com.isim.dims.dto.ObstacleQueryResult;
import com.isim.dims.dto.PerceptionFrameResponse;
import com.isim.dims.dto.RawMessagePageResponse;
import com.isim.dims.dto.ReplayContextResponse;
import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.dto.ReplayExtractionResponse;
import com.isim.dims.dto.ReplayFrameQueryResult;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.ReplayExtractionTaskService;
import com.isim.dims.service.ReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2D 回放 Controller 测试（契约 v1.3.0：200/202/404/409/422 路由与统一错误结构）
 */
@WebMvcTest(ReplayController.class)
@Import(SliceSecurityTestConfig.class)
class ReplayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReplayService replayService;

    @MockBean
    private ReplayExtractionTaskService replayExtractionTaskService;

    // ============ GET /replay/context ============

    @Test
    void getContext_Returns200() throws Exception {
        ReplayContextResponse ctx = new ReplayContextResponse();
        ctx.setSliceId("slice-1");
        ctx.setStartTs("2026-07-24T10:00:00Z");
        ctx.setEndTs("2026-07-24T10:00:30Z");
        ctx.setDataReady(false);

        when(replayService.getContext("slice-1")).thenReturn(ctx);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slice_id").value("slice-1"))
                .andExpect(jsonPath("$.data_ready").value(false));
    }

    @Test
    void getContext_SliceNotFound_Returns404() throws Exception {
        when(replayService.getContext("bad-slice"))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: bad-slice"));

        mockMvc.perform(get("/v1/data-slices/bad-slice/replay/context"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ GET /replay/obstacles ============

    @Test
    void getObstacles_CacheHit_Returns200() throws Exception {
        ObstacleQueryResult result = new ObstacleQueryResult();
        ObstacleFrameResponse frame = new ObstacleFrameResponse();
        frame.setTime("2026-07-24T10:00:00.200Z");
        frame.setTimeNs(1784918400200000000L);
        frame.setFrame("ego");
        frame.setUnit("m");
        frame.setCalibrated(true);
        frame.setObstacles(List.of());
        result.setFrame(frame);

        when(replayService.getObstacles(eq("slice-1"), eq("2026-07-24T10:00:00.200Z"),
                isNull(), isNull())).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/obstacles")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frame").value("ego"))
                .andExpect(jsonPath("$.unit").value("m"))
                .andExpect(jsonPath("$.calibrated").value(true))
                .andExpect(jsonPath("$.time_ns").value(1784918400200000000L));
    }

    @Test
    void getObstacles_CacheMiss_Returns202WithTaskId() throws Exception {
        ObstacleQueryResult result = new ObstacleQueryResult();
        ReplayExtractionResponse taskResp = new ReplayExtractionResponse();
        taskResp.setTaskId("task-123");
        taskResp.setStatus("PENDING");
        result.setTaskResponse(taskResp);

        when(replayService.getObstacles(eq("slice-1"), eq("2026-07-24T10:00:00.200Z"),
                isNull(), isNull())).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/obstacles")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-123"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getObstacles_OtherParamInFlight_Returns409() throws Exception {
        when(replayService.getObstacles(eq("slice-1"), eq("2026-07-24T10:00:00.200Z"),
                isNull(), isNull()))
                .thenThrow(new RuntimeException("REPLAY_EXTRACT_IN_PROGRESS: slice slice-1 busy"));

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/obstacles")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPLAY_EXTRACT_IN_PROGRESS"));
    }

    @Test
    void getObstacles_SliceNotFound_Returns404() throws Exception {
        when(replayService.getObstacles(eq("bad-slice"), eq("2026-07-24T10:00:00.200Z"),
                isNull(), isNull()))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: bad-slice"));

        mockMvc.perform(get("/v1/data-slices/bad-slice/replay/obstacles")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ GET /replay/perception ============

    @Test
    void getPerception_CacheHit_Returns200() throws Exception {
        ReplayFrameQueryResult<PerceptionFrameResponse> result = new ReplayFrameQueryResult<>();
        PerceptionFrameResponse frame = new PerceptionFrameResponse();
        frame.setTime("2026-07-24T10:00:00.200Z");
        frame.setTimeNs(1784918400200000000L);
        frame.setFrame("global");
        frame.setUnit("m");
        frame.setObjects(List.of());
        result.setFrame(frame);

        when(replayService.getPerception(eq("slice-1"), eq("2026-07-24T10:00:00.200Z"),
                isNull())).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/perception")
                        .param("time", "2026-07-24T10:00:00.200Z")
                        .param("sensor_types", "radar,ultrasonic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frame").value("global"))
                .andExpect(jsonPath("$.unit").value("m"))
                .andExpect(jsonPath("$.time_ns").value(1784918400200000000L));
    }

    @Test
    void getPerception_CacheMiss_Returns202WithTaskId() throws Exception {
        ReplayFrameQueryResult<PerceptionFrameResponse> result = new ReplayFrameQueryResult<>();
        ReplayExtractionResponse taskResp = new ReplayExtractionResponse();
        taskResp.setTaskId("task-perc-1");
        taskResp.setStatus("PENDING");
        result.setTaskResponse(taskResp);

        when(replayService.getPerception(eq("slice-1"), eq("2026-07-24T10:00:00.200Z"),
                isNull())).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/perception")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-perc-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getPerception_SliceNotFound_Returns404() throws Exception {
        when(replayService.getPerception(eq("bad-slice"), eq("2026-07-24T10:00:00.200Z"),
                isNull()))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: bad-slice"));

        mockMvc.perform(get("/v1/data-slices/bad-slice/replay/perception")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ GET /replay/map ============

    @Test
    void getMap_CacheHit_Returns200() throws Exception {
        ReplayFrameQueryResult<MapFrameResponse> result = new ReplayFrameQueryResult<>();
        MapFrameResponse frame = new MapFrameResponse();
        frame.setTime("2026-07-24T10:00:00.200Z");
        frame.setTimeNs(1784918400200000000L);
        frame.setFrame("ego");
        frame.setUnit("m");
        frame.setLaneBoundaries(List.of());
        frame.setRoadBoundaries(List.of());
        frame.setStopLines(List.of());
        result.setFrame(frame);

        when(replayService.getMap(eq("slice-1"), eq("2026-07-24T10:00:00.200Z"),
                eq(200))).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/map")
                        .param("time", "2026-07-24T10:00:00.200Z")
                        .param("window_ms", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frame").value("ego"))
                .andExpect(jsonPath("$.unit").value("m"))
                .andExpect(jsonPath("$.time_ns").value(1784918400200000000L));
    }

    @Test
    void getMap_CacheMiss_Returns202WithTaskId() throws Exception {
        ReplayFrameQueryResult<MapFrameResponse> result = new ReplayFrameQueryResult<>();
        ReplayExtractionResponse taskResp = new ReplayExtractionResponse();
        taskResp.setTaskId("task-map-1");
        taskResp.setStatus("RUNNING");
        result.setTaskResponse(taskResp);

        when(replayService.getMap(eq("slice-1"), eq("2026-07-24T10:00:00.200Z"),
                isNull())).thenReturn(result);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/map")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-map-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getMap_SliceNotFound_Returns404() throws Exception {
        when(replayService.getMap(eq("bad-slice"), eq("2026-07-24T10:00:00.200Z"),
                isNull()))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: bad-slice"));

        mockMvc.perform(get("/v1/data-slices/bad-slice/replay/map")
                        .param("time", "2026-07-24T10:00:00.200Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ GET /replay/messages ============

    @Test
    void getMessages_Returns200() throws Exception {
        RawMessagePageResponse page = new RawMessagePageResponse();
        page.setTopic("/perception/radar/result_front");
        page.setType("perception_interface_msgs/msg/RadarPerceptionResult");
        page.setTotal(600L);
        page.setNextOffset(50);
        page.setMessages(List.of());

        when(replayService.getMessages(eq("slice-1"), eq("/perception/radar/result_front"),
                eq("2026-07-24T10:00:00Z"), eq("2026-07-24T10:00:30Z"), eq(0), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/messages")
                        .param("topic", "/perception/radar/result_front")
                        .param("start_ts", "2026-07-24T10:00:00Z")
                        .param("end_ts", "2026-07-24T10:00:30Z")
                        .param("offset", "0")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("/perception/radar/result_front"))
                .andExpect(jsonPath("$.total").value(600))
                .andExpect(jsonPath("$.has_payload").value(true));
    }

    @Test
    void getMessages_UnknownTopic_Returns404() throws Exception {
        when(replayService.getMessages(eq("slice-1"), eq("/unknown/topic"),
                eq("2026-07-24T10:00:00Z"), eq("2026-07-24T10:00:30Z"), eq(0), eq(50)))
                .thenThrow(new RuntimeException("REPLAY_TOPIC_NOT_FOUND: /unknown/topic not in slice slice-1"));

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/messages")
                        .param("topic", "/unknown/topic")
                        .param("start_ts", "2026-07-24T10:00:00Z")
                        .param("end_ts", "2026-07-24T10:00:30Z")
                        .param("offset", "0")
                        .param("limit", "50"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPLAY_TOPIC_NOT_FOUND"));
    }

    @Test
    void getMessages_NoPayloadTopic_Returns422() throws Exception {
        when(replayService.getMessages(eq("slice-1"), eq("/momenta_public/aeb_state"),
                eq("2026-07-24T10:00:00Z"), eq("2026-07-24T10:00:30Z"), eq(0), eq(50)))
                .thenThrow(new RuntimeException("REPLAY_TOPIC_NO_PAYLOAD: header-only topic"));

        mockMvc.perform(get("/v1/data-slices/slice-1/replay/messages")
                        .param("topic", "/momenta_public/aeb_state")
                        .param("start_ts", "2026-07-24T10:00:00Z")
                        .param("end_ts", "2026-07-24T10:00:30Z")
                        .param("offset", "0")
                        .param("limit", "50"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REPLAY_TOPIC_NO_PAYLOAD"));
    }

    // ============ POST /replay/extract ============

    @Test
    void createExtraction_Returns202() throws Exception {
        ReplayExtractionResponse resp = new ReplayExtractionResponse();
        resp.setTaskId("task-1");
        resp.setStatus("PENDING");

        when(replayExtractionTaskService.createTask(eq("slice-1"), any(ReplayExtractionRequest.class)))
                .thenReturn(resp);

        mockMvc.perform(post("/v1/data-slices/slice-1/replay/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"obstacles\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createExtraction_OtherParamInFlight_Returns409() throws Exception {
        when(replayExtractionTaskService.createTask(eq("slice-1"), any(ReplayExtractionRequest.class)))
                .thenThrow(new RuntimeException("REPLAY_EXTRACT_IN_PROGRESS: slice busy"));

        mockMvc.perform(post("/v1/data-slices/slice-1/replay/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"messages\",\"topics\":[\"/sensor/imu\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPLAY_EXTRACT_IN_PROGRESS"));
    }

    @Test
    void createExtraction_InvalidParam_Returns400() throws Exception {
        when(replayExtractionTaskService.createTask(eq("slice-1"), any(ReplayExtractionRequest.class)))
                .thenThrow(new RuntimeException("INVALID_PARAMETER: type must be obstacles or messages"));

        mockMvc.perform(post("/v1/data-slices/slice-1/replay/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    // ============ W2/W3（契约 v1.3.1）：重试超限 409 / topics 校验 404/422 ============

    @Test
    void createExtraction_RetryExceeded_Returns409RetryExceeded() throws Exception {
        when(replayExtractionTaskService.createTask(eq("slice-1"), any(ReplayExtractionRequest.class)))
                .thenThrow(new RuntimeException("REPLAY_EXTRACT_RETRY_EXCEEDED: retry limit reached (1)"));

        mockMvc.perform(post("/v1/data-slices/slice-1/replay/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"obstacles\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPLAY_EXTRACT_RETRY_EXCEEDED"));
    }

    @Test
    void createExtraction_UnknownTopic_Returns404() throws Exception {
        when(replayExtractionTaskService.createTask(eq("slice-1"), any(ReplayExtractionRequest.class)))
                .thenThrow(new RuntimeException("REPLAY_TOPIC_NOT_FOUND: /unknown/topic not in slice slice-1"));

        mockMvc.perform(post("/v1/data-slices/slice-1/replay/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"messages\",\"topics\":[\"/unknown/topic\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPLAY_TOPIC_NOT_FOUND"));
    }

    @Test
    void createExtraction_NoPayloadTopic_Returns422() throws Exception {
        when(replayExtractionTaskService.createTask(eq("slice-1"), any(ReplayExtractionRequest.class)))
                .thenThrow(new RuntimeException("REPLAY_TOPIC_NO_PAYLOAD: header-only topic"));

        mockMvc.perform(post("/v1/data-slices/slice-1/replay/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"messages\",\"topics\":[\"/momenta_public/aeb_state\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REPLAY_TOPIC_NO_PAYLOAD"));
    }
}
