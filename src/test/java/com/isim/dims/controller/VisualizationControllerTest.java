package com.isim.dims.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.SignalDataResponse;
import com.isim.dims.dto.VideoStreamResponse;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.VisualizationPipelineService;
import com.isim.dims.service.VisualizationService;
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

@WebMvcTest(VisualizationController.class)
@Import(SliceSecurityTestConfig.class)
class VisualizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VisualizationService visualizationService;

    @MockBean
    private VisualizationPipelineService visualizationPipelineService;

    @MockBean
    private com.isim.dims.service.VideoImageService videoImageService;

    // ============ GET /v1/data-slices/{sliceId}/video-streams ============

    @Test
    void getVideoStreams_Returns200() throws Exception {
        VideoStreamResponse response = new VideoStreamResponse();

        // 已提取的视频流（extracted = true）
        VideoStreamResponse.VideoStreamItem extractedStream = new VideoStreamResponse.VideoStreamItem();
        extractedStream.setSensorName("F30_front_far");
        extractedStream.setTopic("/sensor/camera_front_far/video");
        extractedStream.setVideoFile("F30_front_far.h265");
        extractedStream.setWidth(3840);
        extractedStream.setHeight(2160);
        extractedStream.setFps(10.0);
        extractedStream.setUrl("/data/videos/slice-1/_sensor_camera_front_far_video.mp4");
        extractedStream.setExtracted(true);

        // 尚未提取的视频流（extracted = false，url 仍返回拼好的地址）
        VideoStreamResponse.VideoStreamItem notExtractedStream = new VideoStreamResponse.VideoStreamItem();
        notExtractedStream.setSensorName("camera_front_wide");
        notExtractedStream.setTopic("/sensor/camera_front_wide/video");
        notExtractedStream.setVideoFile("F30_front_wide.h265");
        notExtractedStream.setWidth(1920);
        notExtractedStream.setHeight(1080);
        notExtractedStream.setFps(10.0);
        notExtractedStream.setUrl("/data/videos/slice-1/_sensor_camera_front_wide_video.mp4");
        notExtractedStream.setExtracted(false);

        response.setVideoStreams(List.of(extractedStream, notExtractedStream));

        when(visualizationService.getVideoStreams(eq("slice-1"), isNull())).thenReturn(response);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/video-streams", "slice-1"))
                .andExpect(status().isOk())
                // 原 5 字段向后兼容
                .andExpect(jsonPath("$.video_streams[0].sensor_name").value("F30_front_far"))
                .andExpect(jsonPath("$.video_streams[0].video_file").value("F30_front_far.h265"))
                .andExpect(jsonPath("$.video_streams[0].width").value(3840))
                .andExpect(jsonPath("$.video_streams[0].height").value(2160))
                .andExpect(jsonPath("$.video_streams[0].fps").value(10.0))
                // 新增 3 字段：topic / url / extracted
                .andExpect(jsonPath("$.video_streams[0].topic").value("/sensor/camera_front_far/video"))
                .andExpect(jsonPath("$.video_streams[0].url").value("/data/videos/slice-1/_sensor_camera_front_far_video.mp4"))
                .andExpect(jsonPath("$.video_streams[0].extracted").value(true))
                // extracted = false 场景
                .andExpect(jsonPath("$.video_streams[1].topic").value("/sensor/camera_front_wide/video"))
                .andExpect(jsonPath("$.video_streams[1].url").value("/data/videos/slice-1/_sensor_camera_front_wide_video.mp4"))
                .andExpect(jsonPath("$.video_streams[1].extracted").value(false));
    }

    @Test
    void getVideoStreams_NoCameraStreams_ReturnsEmptyList() throws Exception {
        VideoStreamResponse response = new VideoStreamResponse();
        response.setVideoStreams(List.of());

        when(visualizationService.getVideoStreams(eq("slice-1"), isNull())).thenReturn(response);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/video-streams", "slice-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video_streams").isArray())
                .andExpect(jsonPath("$.video_streams").isEmpty());
    }

    @Test
    void getVideoStreams_SliceNotFound_Returns500() throws Exception {
        when(visualizationService.getVideoStreams(eq("invalid"), isNull()))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: invalid"));

        mockMvc.perform(get("/v1/data-slices/{sliceId}/video-streams", "invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("VIDEO_STREAMS_FAILED"));
    }

    // ============ POST /v1/data-slices/{sliceId}/video-streams/extract ============

    @Test
    void extractVideoSegment_Returns202() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/camera_front_wide/video"));

        when(visualizationService.extractVideoSegment(eq("slice-1"), any(ExtractRequest.class)))
                .thenReturn(Map.of("task_id", "task-v1", "status", "PROCESSING"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/video-streams/extract", "slice-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-v1"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void extractVideoSegment_ServiceThrowsException_Returns500() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/camera/video"));

        when(visualizationService.extractVideoSegment(eq("invalid"), any(ExtractRequest.class)))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: invalid"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/video-streams/extract", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("VIDEO_EXTRACT_FAILED"));
    }

    // ============ POST /v1/data-slices/{sliceId}/signal-data ============

    @Test
    void extractSignalData_Returns202() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/vehicle/chassis"));
        request.setStartTs("2026-07-29T10:00:00Z");
        request.setEndTs("2026-07-29T10:10:00Z");
        request.setMaxPointsPerSignal(10000);

        when(visualizationService.extractSignalData(eq("slice-1"), any(ExtractRequest.class)))
                .thenReturn(Map.of("task_id", "task-s1", "status", "PROCESSING"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/signal-data", "slice-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task_id").value("task-s1"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void extractSignalData_ServiceThrowsException_Returns500() throws Exception {
        ExtractRequest request = new ExtractRequest();
        request.setTopics(List.of("/sensor/vehicle/chassis"));

        when(visualizationService.extractSignalData(eq("invalid"), any(ExtractRequest.class)))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: invalid"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/signal-data", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("SIGNAL_EXTRACT_FAILED"));
    }

    // ============ GET /v1/signal-extractions/{taskId} ============

    @Test
    void getSignalExtraction_Returns200() throws Exception {
        SignalDataResponse response = new SignalDataResponse();
        response.setTaskId("task-s1");
        response.setSliceId("slice-1");
        response.setStatus("READY");

        SignalDataResponse.SignalSeries series = new SignalDataResponse.SignalSeries();
        series.setTopic("/sensor/vehicle/chassis");
        series.setSignalName("speed_mps");
        series.setDisplayName("车速");
        series.setUnit("m/s");

        SignalDataResponse.SignalDataPoint point = new SignalDataResponse.SignalDataPoint();
        point.setTs("2026-07-29T10:00:00Z");
        point.setValue(0.0);
        series.setData(List.of(point));

        response.setSignals(List.of(series));

        when(visualizationService.getSignalExtraction("task-s1")).thenReturn(response);

        mockMvc.perform(get("/v1/signal-extractions/{taskId}", "task-s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("task-s1"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.signals[0].signal_name").value("speed_mps"))
                .andExpect(jsonPath("$.signals[0].data[0].value").value(0.0));
    }

    @Test
    void getSignalExtraction_TaskNotFound_Returns500() throws Exception {
        when(visualizationService.getSignalExtraction("invalid-task"))
                .thenThrow(new RuntimeException("SIGNAL_EXTRACTION_TASK_NOT_FOUND: invalid-task"));

        mockMvc.perform(get("/v1/signal-extractions/{taskId}", "invalid-task"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("SIGNAL_EXTRACTION_FAILED"));
    }
}
