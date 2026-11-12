package com.isim.dims.service;

import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.dto.ReplayExtractionResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.ReplayExtractionTask;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ReplayExtractionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.impl.ReplayExtractionTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回放提取任务 Service 测试
 *
 * 覆盖契约要点：同参进行中去重返回同一 task_id、跨参进行中 409（按 slice 串行）、
 * FAILED 允许重试 1 次、任务查询 404。
 */
@ExtendWith(MockitoExtension.class)
class ReplayExtractionTaskServiceImplTest {

    @Mock
    private ReplayExtractionTaskMapper taskMapper;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private SensorStreamMapper sensorStreamMapper;

    @Mock
    private ReplayExtractionPipelineService pipelineService;

    @InjectMocks
    private ReplayExtractionTaskServiceImpl taskService;

    private DataSlice slice;

    @BeforeEach
    void setUp() {
        slice = new DataSlice();
        slice.setId("slice-1");
        slice.setFilePath("/data/bags/slice-1/original.bag");
        slice.setStartTs(LocalDateTime.of(2026, 7, 24, 10, 0, 0));
        slice.setEndTs(LocalDateTime.of(2026, 7, 24, 10, 0, 30));
        // lenient：部分测试（getTask/SliceNotFound）不经过 createTask，不需要该 stubbing
        lenient().when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        // W3：createTask 会同步校验显式 topics（查询切片 topic 清单）；
        // 默认清单含请求使用的 /perception/radar/result_front
        SensorStream radarStream = new SensorStream();
        radarStream.setTopic("/perception/radar/result_front");
        radarStream.setSensorCategory("radar");
        lenient().when(sensorStreamMapper.selectList(any()))
                .thenReturn(List.of(radarStream));
    }

    private ReplayExtractionRequest obstaclesRequest() {
        ReplayExtractionRequest req = new ReplayExtractionRequest();
        req.setType("obstacles");
        req.setTopics(List.of("/perception/radar/result_front"));
        return req;
    }

    private ReplayExtractionTask task(String id, String status) {
        ReplayExtractionTask t = new ReplayExtractionTask();
        t.setId(id);
        t.setSliceId("slice-1");
        t.setType("obstacles");
        t.setTopics("[\"/perception/radar/result_front\"]");
        t.setStartTs(slice.getStartTs());
        t.setEndTs(slice.getEndTs());
        t.setStatus(status);
        t.setProgress(0);
        t.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return t;
    }

    // ============ 同参进行中去重 ============

    @Test
    void createTask_SameParamsInFlight_ReturnsSameTaskId() {
        ReplayExtractionTask inFlight = task("task-inflight", "RUNNING");
        when(taskMapper.selectList(any())).thenReturn(List.of(inFlight));

        ReplayExtractionResponse resp = taskService.createTask("slice-1", obstaclesRequest());

        assertThat(resp.getTaskId()).isEqualTo("task-inflight");
        assertThat(resp.getStatus()).isEqualTo("RUNNING");
        verify(taskMapper, never()).insert(any(ReplayExtractionTask.class));
        verify(pipelineService, never()).processExtraction(any(), any(), any());
    }

    // ============ 跨参进行中 → 409（按 slice 串行） ============

    @Test
    void createTask_DifferentParamsInFlight_ThrowsConflict() {
        ReplayExtractionTask inFlight = task("task-other", "RUNNING");
        inFlight.setTopics("[\"/sensor/imu\"]");
        inFlight.setType("messages");
        when(taskMapper.selectList(any())).thenReturn(List.of(inFlight));

        assertThatThrownBy(() -> taskService.createTask("slice-1", obstaclesRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("REPLAY_EXTRACT_IN_PROGRESS");
    }

    // ============ 新建任务 → PENDING + 异步派发 ============

    @Test
    void createTask_NoInFlight_CreatesPendingAndDispatches() {
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.insert(any(ReplayExtractionTask.class))).thenReturn(1);

        ReplayExtractionResponse resp = taskService.createTask("slice-1", obstaclesRequest());

        assertThat(resp.getStatus()).isEqualTo("PENDING");
        assertThat(resp.getTaskId()).isNotBlank();
        verify(taskMapper).insert(any(ReplayExtractionTask.class));
        verify(pipelineService).processExtraction(any(), eq("/data/bags/slice-1/original.bag"), any());
    }

    // ============ FAILED 允许重试 1 次 ============

    @Test
    void createTask_OneFailedSameParams_AllowsRetry() {
        ReplayExtractionTask failed = task("task-failed", "FAILED");
        failed.setMessage("python error");
        // 第 1 次 selectList：无进行中；第 2 次 selectList：1 个同参 FAILED（重试上限内）
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(failed));
        when(taskMapper.selectOne(any())).thenReturn(null);              // 无最近 SUCCESS
        when(taskMapper.insert(any(ReplayExtractionTask.class))).thenReturn(1);

        ReplayExtractionResponse resp = taskService.createTask("slice-1", obstaclesRequest());

        assertThat(resp.getStatus()).isEqualTo("PENDING");
        verify(pipelineService).processExtraction(any(), any(), any());
    }

    @Test
    void createTask_TwoFailedSameParams_ThrowsRetryLimitReached() {
        ReplayExtractionTask failed1 = task("task-failed-1", "FAILED");
        ReplayExtractionTask failed2 = task("task-failed-2", "FAILED");
        failed2.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        // 第 1 次 selectList：无进行中；第 2 次 selectList：2 个同参 FAILED（超重试上限）
        when(taskMapper.selectList(any())).thenReturn(List.of(), List.of(failed1, failed2));
        when(taskMapper.selectOne(any())).thenReturn(null);              // 无最近 SUCCESS

        assertThatThrownBy(() -> taskService.createTask("slice-1", obstaclesRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("REPLAY_EXTRACT_RETRY_EXCEEDED");
    }

    // ============ 参数校验 ============

    @Test
    void createTask_InvalidType_Throws() {
        ReplayExtractionRequest req = obstaclesRequest();
        req.setType("bogus");

        assertThatThrownBy(() -> taskService.createTask("slice-1", req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("INVALID_PARAMETER");
    }

    @Test
    void createTask_MessagesWithoutTopics_Throws() {
        ReplayExtractionRequest req = new ReplayExtractionRequest();
        req.setType("messages");

        assertThatThrownBy(() -> taskService.createTask("slice-1", req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("INVALID_PARAMETER");
    }

    // ============ W3（契约 v1.3.1）：创建前 topics 同步校验 ============

    @Test
    void createTask_UnknownTopic_ThrowsNotFound() {
        // 覆盖 setUp 的默认清单：切片无该 topic → 404 REPLAY_TOPIC_NOT_FOUND
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> taskService.createTask("slice-1", obstaclesRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("REPLAY_TOPIC_NOT_FOUND");
        // 校验在创建任务前完成：不占串行队列、不浪费重试额度
        verify(taskMapper, never()).insert(any(ReplayExtractionTask.class));
    }

    // ============ v1.4 修正：/momenta_public/* 不再排除 ============

    @Test
    void createTask_MomentaPublicTopic_IsAccepted() {
        // v1.4 修正：/momenta_public/* 实测 frame_id 内嵌 JSON 业务负载，
        // Raw 消息提取不再 422，任务正常创建
        SensorStream aebStream = new SensorStream();
        aebStream.setTopic("/momenta_public/aeb_state");
        aebStream.setSensorCategory("vehicle_signal");
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of(aebStream));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(ReplayExtractionTask.class))).thenReturn(1);

        ReplayExtractionRequest req = new ReplayExtractionRequest();
        req.setType("messages");
        req.setTopics(List.of("/momenta_public/aeb_state"));

        ReplayExtractionResponse resp = taskService.createTask("slice-1", req);

        assertThat(resp.getStatus()).isEqualTo("PENDING");
        verify(taskMapper).insert(any(ReplayExtractionTask.class));
        verify(pipelineService).processExtraction(any(), any(), any());
    }

    // ============ v1.4 新增 type=perception / type=map 默认 topic ============

    @Test
    void createTask_PerceptionEmptyTopics_UsesSmoothObjectDefault() {
        SensorStream smoothStream = new SensorStream();
        smoothStream.setTopic("/momenta_public/perception/fusion/smooth_object");
        smoothStream.setSensorCategory("adas");
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of(smoothStream));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(ReplayExtractionTask.class))).thenReturn(1);

        ReplayExtractionRequest req = new ReplayExtractionRequest();
        req.setType("perception"); // topics 为空 → 默认感知 topic

        ReplayExtractionResponse resp = taskService.createTask("slice-1", req);

        assertThat(resp.getStatus()).isEqualTo("PENDING");
        ArgumentCaptor<ReplayExtractionTask> captor = ArgumentCaptor.forClass(ReplayExtractionTask.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("perception");
        assertThat(captor.getValue().getTopics())
                .contains("/momenta_public/perception/fusion/smooth_object");
    }

    @Test
    void createTask_MapEmptyTopics_UsesLandmarkVisDefault() {
        SensorStream mapStream = new SensorStream();
        mapStream.setTopic("/momenta_public/ddld/landmark_vis");
        mapStream.setSensorCategory("other");
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of(mapStream));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(ReplayExtractionTask.class))).thenReturn(1);

        ReplayExtractionRequest req = new ReplayExtractionRequest();
        req.setType("map"); // topics 为空 → 默认地图 topic

        ReplayExtractionResponse resp = taskService.createTask("slice-1", req);

        assertThat(resp.getStatus()).isEqualTo("PENDING");
        ArgumentCaptor<ReplayExtractionTask> captor = ArgumentCaptor.forClass(ReplayExtractionTask.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("map");
        assertThat(captor.getValue().getTopics()).contains("/momenta_public/ddld/landmark_vis");
    }

    @Test
    void createTask_SliceNotFound_Throws() {
        when(dataSliceMapper.selectById("bad-slice")).thenReturn(null);

        assertThatThrownBy(() -> taskService.createTask("bad-slice", obstaclesRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("SLICE_NOT_FOUND");
    }

    // ============ 查询任务 ============

    @Test
    void getTask_NotFound_Throws() {
        when(taskMapper.selectById("no-such-task")).thenReturn(null);

        assertThatThrownBy(() -> taskService.getTask("no-such-task"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("TASK_NOT_FOUND");
    }

    @Test
    void getTask_ReturnsDetail() {
        ReplayExtractionTask t = task("task-1", "SUCCESS");
        t.setProgress(100);
        t.setOutputPath("/data/cache/slice-1/replay/obstacles");
        t.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(taskMapper.selectById("task-1")).thenReturn(t);

        var resp = taskService.getTask("task-1");

        assertThat(resp.getTaskId()).isEqualTo("task-1");
        assertThat(resp.getSliceId()).isEqualTo("slice-1");
        assertThat(resp.getType()).isEqualTo("obstacles");
        assertThat(resp.getStatus()).isEqualTo("SUCCESS");
        assertThat(resp.getTopics()).containsExactly("/perception/radar/result_front");
        assertThat(resp.getOutputSummary()).containsKey("output_path");
    }
}
