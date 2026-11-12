package com.isim.dims.service;

import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.SceneExtractionResponse;
import com.isim.dims.entity.CfdiMeta;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.DataSliceTag;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.SceneExtractionTask;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.entity.SliceStats;
import com.isim.dims.mapper.CfdiMetaMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.SceneExtractionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.mapper.SliceStatsMapper;
import com.isim.dims.service.DashboardStatsPipelineService;
import com.isim.dims.service.SceneExtractionPipelineService;
import com.isim.dims.service.VisualizationPipelineService;
import com.isim.dims.service.VisualizationService;
import com.isim.dims.service.impl.IngestionPipelineServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
import com.isim.dims.util.PythonExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class IngestionPipelineServiceImplTest {

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private CfdiMetaMapper cfdiMetaMapper;

    @Mock
    private SensorStreamMapper sensorStreamMapper;

    @Mock
    private DataSliceTagMapper dataSliceTagMapper;

    @Mock
    private IngestionTaskMapper ingestionTaskMapper;

    @Mock
    private SimsTagCatalog simsTagCatalog;

    @Mock
    private PythonExecutor pythonExecutor;

    @Mock
    private IngestionService ingestionService;

    @Mock
    private SceneExtractionPipelineService sceneExtractionPipelineService;

    @Mock
    private DashboardStatsPipelineService dashboardStatsPipelineService;

    @Mock
    private VisualizationService visualizationService;

    @Mock
    private VisualizationPipelineService visualizationPipelineService;

    @Mock
    private StorageAccess storageAccess;

    @Mock
    private SliceStatsMapper sliceStatsMapper;

    @Mock
    private SceneExtractionTaskMapper sceneExtractionTaskMapper;

    @InjectMocks
    private IngestionPipelineServiceImpl pipelineService;

    @Captor
    private ArgumentCaptor<DataSlice> sliceCaptor;

    private final List<String> capturedTaskStatuses = new ArrayList<>();
    private final List<Integer> capturedTaskProgresses = new ArrayList<>();
    private final List<String> capturedSliceStatuses = new ArrayList<>();

    // ============ processParsePipeline() - Happy Path ============

    @BeforeEach
    void stubStorageMaterialize() {
        // Python 进程只能消费本地路径：物化存储引用返回原路径（local 语义零拷贝）。
        // lenient：TaskNotFound 等提前返回的用例不消费该 stub。
        lenient().when(storageAccess.materializeLocal(anyString()))
                .thenAnswer(invocation -> Path.of((String) invocation.getArgument(0)));
        // @Autowired(required=false) 可选字段不进构造器，@InjectMocks 字段注入不生效，
        // 显式注入 mock（minio 模式停用统计流水线，此处模拟 local 模式装配）。
        ReflectionTestUtils.setField(pipelineService, "dashboardStatsPipelineService",
                dashboardStatsPipelineService);
    }

    @Test
    void processParsePipeline_HappyPath_TransitionsThroughAllStates() {
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("UPLOADING");
        task.setProgress(0);

        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);
        // Simulate IngestionService.updateTaskStatus to modify task in-place and trigger updateById
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            int progress = invocation.getArgument(2);
            t.setStatus(status);
            t.setProgress(progress);
            ingestionTaskMapper.updateById(t);
            return null;
        }).when(ingestionService).updateTaskStatus(any(IngestionTask.class), anyString(), anyInt());
        // Capture task state at each updateById call (avoids mutable reference issue)
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            capturedTaskStatuses.add(t.getStatus());
            capturedTaskProgresses.add(t.getProgress());
            return 1;
        }).when(ingestionTaskMapper).updateById(any(IngestionTask.class));

        String parseResult = "{"
                + "\"start_ts\":\"2026-07-24T09:00:00Z\","
                + "\"end_ts\":\"2026-07-24T09:30:00Z\","
                + "\"duration_ms\":1800000,"
                + "\"msg_count_total\":15000,"
                + "\"gps_bbox\":{\"min_lat\":31.2,\"min_lon\":121.4,\"max_lat\":31.3,\"max_lon\":121.5},"
                + "\"streams\":[{"
                + "  \"topic\":\"/sensor/camera_front\","
                + "  \"msg_type\":\"sensor_msgs/Image\","
                + "  \"sensor_category\":\"camera\","
                + "  \"msg_count\":500,"
                + "  \"frequency_hz\":30.0,"
                + "  \"first_ts\":\"2026-07-24T09:00:00Z\","
                + "  \"last_ts\":\"2026-07-24T09:30:00Z\""
                + "}]"
                + "}";

        when(pythonExecutor.execute(eq("parse_rosbag.py"), anyMap(), eq(300)))
                .thenReturn(parseResult);

        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("UPLOADING");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        // Capture slice state at call time
        doAnswer(invocation -> {
            DataSlice s = invocation.getArgument(0);
            capturedSliceStatuses.add(s.getStatus());
            return 1;
        }).when(dataSliceMapper).updateById(any(DataSlice.class));

        String tagResult = "{\"tag_ids\":[\"tag-004\",\"tag-010\"]}";
        when(pythonExecutor.execute(eq("auto_tag.py"), anyMap(), eq(120)))
                .thenReturn(tagResult);

        when(simsTagCatalog.find(any())).thenReturn(new com.isim.dims.dto.TagRef("tag-1", "高速", null));

        // 同步提取阶段 stub：相机流 + 视频/统计/场景全部立即 SUCCESS
        SensorStream cameraStream = new SensorStream();
        cameraStream.setId("stream-1");
        cameraStream.setSliceId("slice-1");
        cameraStream.setTopic("/sensor/camera_front");
        cameraStream.setSensorCategory("camera");
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of(cameraStream));
        when(visualizationService.extractVideoSegment(eq("slice-1"), any(ExtractRequest.class)))
                .thenReturn(Map.of("task_id", "video-1", "status", "PROCESSING"));
        when(visualizationPipelineService.getVideoExtractionTask("video-1"))
                .thenReturn(Map.of("status", "READY"));
        stubStatsSceneSuccess();

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        // Verify task status transitions (captured at call time)
        assertThat(capturedTaskStatuses).containsExactly(
                "PARSING", "TAGGING", "VIDEO_EXTRACTING", "STATS_EXTRACTING", "SCENE_EXTRACTING", "READY");
        assertThat(capturedTaskProgresses).containsExactly(30, 70, 75, 85, 95, 100);

        // Verify slice status transitions (captured at call time)
        assertThat(capturedSliceStatuses).containsExactly(
                "TAGGING", "VIDEO_EXTRACTING", "STATS_EXTRACTING", "SCENE_EXTRACTING", "READY");

        // Verify SensorStream inserted
        verify(sensorStreamMapper).insert(argThat((SensorStream stream) ->
                stream.getTopic().equals("/sensor/camera_front")
                        && stream.getSensorCategory().equals("camera")
                        && stream.getMsgCount() == 500
                        && stream.getFrequencyHz() == 30.0
        ));

        // Verify auto tags inserted
        verify(dataSliceTagMapper, times(2)).insert(any(DataSliceTag.class));

        // Verify READY is the final state
        assertThat(capturedSliceStatuses.get(capturedSliceStatuses.size() - 1)).isEqualTo("READY");

        // Verify stats / scene extraction triggered synchronously (before READY)
        verify(dashboardStatsPipelineService).triggerExtraction("slice-1");
        verify(sceneExtractionPipelineService).triggerExtraction("slice-1");
    }

    /**
     * stub 统计/场景提取为立即 SUCCESS（同步等待阶段的轮询首次即命中终态）
     */
    private void stubStatsSceneSuccess() {
        SliceStats stats = new SliceStats();
        stats.setSliceId("slice-1");
        stats.setStatus("SUCCESS");
        when(sliceStatsMapper.selectOne(any())).thenReturn(stats);

        SceneExtractionResponse sceneResp = new SceneExtractionResponse();
        sceneResp.setTaskId("scene-1");
        sceneResp.setStatus("PENDING");
        when(sceneExtractionPipelineService.triggerExtraction("slice-1")).thenReturn(sceneResp);

        SceneExtractionTask sceneTask = new SceneExtractionTask();
        sceneTask.setId("scene-1");
        sceneTask.setSliceId("slice-1");
        sceneTask.setStatus("SUCCESS");
        sceneTask.setTotalScenes(0);
        when(sceneExtractionTaskMapper.selectById("scene-1")).thenReturn(sceneTask);
    }

    /**
     * CFDI utc_diff_us 不透传回归测试（TASK-058 之前的刻意行为变更）：
     * 实测设备时钟已接近 UTC（偏差仅数小时），而 CFDI meta 中的 utc_diff_us
     * 为 ~56.5 年正偏移，透传给 parse_rosbag.py 会生成 2083 年时间戳，
     * 触发会话时间戳 reconciliation 后导致视频提取窗口与实际帧时间戳
     * 偏差数小时 → "No video frames found"。因此：
     *   - parse_rosbag.py 参数不得包含 utc_diff_us（任何情况下）；
     *   - cfdi_meta 缺失（无 meta_data 可解析）时 utcDiffUs 保持 null，不覆盖既有值。
     */
    @Test
    void processParsePipeline_CfdiUtcDiffUs_NotForwardedToPythonAndPersistedAsNull() {
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("UPLOADING");
        task.setProgress(0);

        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);
        // Simulate IngestionService.updateTaskStatus to modify task in-place
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            t.setStatus(invocation.getArgument(1));
            t.setProgress(invocation.getArgument(2));
            ingestionTaskMapper.updateById(t);
            return null;
        }).when(ingestionService).updateTaskStatus(any(IngestionTask.class), anyString(), anyInt());

        String parseResult = "{"
                + "\"start_ts\":\"2026-07-24T09:00:00Z\","
                + "\"end_ts\":\"2026-07-24T09:30:00Z\","
                + "\"duration_ms\":1800000,"
                + "\"msg_count_total\":15000,"
                + "\"streams\":[{"
                + "  \"topic\":\"/sensor/camera_front\","
                + "  \"msg_type\":\"sensor_msgs/Image\","
                + "  \"sensor_category\":\"camera\","
                + "  \"msg_count\":500"
                + "}]"
                + "}";
        when(pythonExecutor.execute(eq("parse_rosbag.py"), anyMap(), eq(300)))
                .thenReturn(parseResult);

        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("UPLOADING");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        when(pythonExecutor.execute(eq("auto_tag.py"), anyMap(), eq(120)))
                .thenReturn("{\"tag_ids\":[]}");

        stubStatsSceneSuccess();

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        // parse_rosbag.py 调用参数不得包含 utc_diff_us（刻意不透传，见方法 javadoc）
        verify(pythonExecutor).execute(eq("parse_rosbag.py"), argThat((Map<String, Object> args) ->
                !args.containsKey("utc_diff_us")), eq(300));
        // cfdi_meta 缺失时 utc_diff_us 不落库（保持 null，不覆盖既有值）
        verify(dataSliceMapper, atLeastOnce()).updateById(argThat((DataSlice s) ->
                s.getUtcDiffUs() == null));
    }

    /**
     * CFDI meta 有 utc_diff_us 时：只落库供详情页「UTC偏移」展示，仍不透传 parse_rosbag.py。
     * 语义：trigger_timestamp_us（本地数据面触发时刻）+ utc_diff_us（本地时钟→UTC 偏移）
     *     = 事件真实 UTC 触发时间；bag 消息时间戳本身已接近 UTC，不得加偏移。
     */
    @Test
    void processParsePipeline_CfdiUtcDiffUs_PersistedFromMetaButNotForwardedToPython() {
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("UPLOADING");
        task.setProgress(0);

        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            t.setStatus(invocation.getArgument(1));
            t.setProgress(invocation.getArgument(2));
            ingestionTaskMapper.updateById(t);
            return null;
        }).when(ingestionService).updateTaskStatus(any(IngestionTask.class), anyString(), anyInt());

        String parseResult = "{"
                + "\"start_ts\":\"2026-07-24T09:00:00Z\","
                + "\"end_ts\":\"2026-07-24T09:30:00Z\","
                + "\"duration_ms\":1800000,"
                + "\"msg_count_total\":15000,"
                + "\"streams\":[{"
                + "  \"topic\":\"/sensor/camera_front\","
                + "  \"msg_type\":\"sensor_msgs/Image\","
                + "  \"sensor_category\":\"camera\","
                + "  \"msg_count\":500"
                + "}]"
                + "}";
        when(pythonExecutor.execute(eq("parse_rosbag.py"), anyMap(), eq(300)))
                .thenReturn(parseResult);

        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("UPLOADING");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        CfdiMeta meta = new CfdiMeta();
        meta.setSliceId("slice-1");
        meta.setMetaData("{\"utc_diff_us\": 1783560636665049}");
        when(cfdiMetaMapper.selectOne(any())).thenReturn(meta);

        when(pythonExecutor.execute(eq("auto_tag.py"), anyMap(), eq(120)))
                .thenReturn("{\"tag_ids\":[]}");

        stubStatsSceneSuccess();

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        // parse_rosbag.py 参数仍不得包含 utc_diff_us（任何情况下）
        verify(pythonExecutor).execute(eq("parse_rosbag.py"), argThat((Map<String, Object> args) ->
                !args.containsKey("utc_diff_us")), eq(300));
        // cfdi_meta 有值时 utc_diff_us 落库（供详情页展示）
        verify(dataSliceMapper, atLeastOnce()).updateById(argThat((DataSlice s) ->
                s.getUtcDiffUs() != null && s.getUtcDiffUs() == 1783560636665049L));
    }

    @Test
    void processParsePipeline_ParsingFailure_SetsError() {
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("UPLOADING");
        task.setProgress(0);

        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);
        // Simulate IngestionService.updateTaskStatus to modify task in-place and trigger updateById
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            int progress = invocation.getArgument(2);
            t.setStatus(status);
            t.setProgress(progress);
            ingestionTaskMapper.updateById(t);
            return null;
        }).when(ingestionService).updateTaskStatus(any(IngestionTask.class), anyString(), anyInt());

        when(pythonExecutor.execute(eq("parse_rosbag.py"), anyMap(), eq(300)))
                .thenThrow(new RuntimeException("Python parse_rosbag.py failed with exit code 1"));

        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        // Verify ERROR state on task (same reference was modified in-place)
        assertThat(task.getStatus()).isEqualTo("ERROR");
        assertThat(task.getErrorMsg()).contains("Python parse_rosbag.py");

        verify(dataSliceMapper, atLeastOnce()).updateById(sliceCaptor.capture());
        DataSlice errorSlice = sliceCaptor.getAllValues().stream()
                .filter(s -> "ERROR".equals(s.getStatus()))
                .findFirst().orElse(null);
        assertThat(errorSlice).isNotNull();
        assertThat(errorSlice.getErrorMsg()).contains("Python parse_rosbag.py");
    }

    @Test
    void processParsePipeline_AutoTaggingFailure_SetsError() {
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("UPLOADING");
        task.setProgress(0);

        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);
        // Simulate IngestionService.updateTaskStatus to modify task in-place and trigger updateById
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            int progress = invocation.getArgument(2);
            t.setStatus(status);
            t.setProgress(progress);
            ingestionTaskMapper.updateById(t);
            return null;
        }).when(ingestionService).updateTaskStatus(any(IngestionTask.class), anyString(), anyInt());

        String parseResult = "{"
                + "\"start_ts\":\"2026-07-24T09:00:00Z\","
                + "\"end_ts\":\"2026-07-24T09:30:00Z\","
                + "\"duration_ms\":1800000,"
                + "\"msg_count_total\":1000,"
                + "\"streams\":[]"
                + "}";
        when(pythonExecutor.execute(eq("parse_rosbag.py"), anyMap(), eq(300)))
                .thenReturn(parseResult);

        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("UPLOADING");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        when(pythonExecutor.execute(eq("auto_tag.py"), anyMap(), eq(120)))
                .thenThrow(new RuntimeException("Python auto_tag.py failed"));

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        // Verify ERROR state on task
        assertThat(task.getStatus()).isEqualTo("ERROR");

        verify(dataSliceMapper, atLeastOnce()).updateById(sliceCaptor.capture());
        DataSlice errorSlice = sliceCaptor.getAllValues().stream()
                .filter(s -> "ERROR".equals(s.getStatus()))
                .findFirst().orElse(null);
        assertThat(errorSlice).isNotNull();
    }

    @Test
    void processParsePipeline_NoStreams_HandlesGracefully() {
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("UPLOADING");

        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);
        // Simulate IngestionService.updateTaskStatus to modify task in-place and trigger updateById
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            int progress = invocation.getArgument(2);
            t.setStatus(status);
            t.setProgress(progress);
            ingestionTaskMapper.updateById(t);
            return null;
        }).when(ingestionService).updateTaskStatus(any(IngestionTask.class), anyString(), anyInt());

        String parseResult = "{"
                + "\"start_ts\":\"2026-07-24T09:00:00Z\","
                + "\"end_ts\":\"2026-07-24T09:30:00Z\","
                + "\"duration_ms\":30000,"
                + "\"msg_count_total\":500,"
                + "\"streams\":[]"
                + "}";
        when(pythonExecutor.execute(eq("parse_rosbag.py"), anyMap(), eq(300)))
                .thenReturn(parseResult);

        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("UPLOADING");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        String tagResult = "{\"tag_ids\":[\"tag-004\"]}";
        when(pythonExecutor.execute(eq("auto_tag.py"), anyMap(), eq(120)))
                .thenReturn(tagResult);
        when(simsTagCatalog.find(any())).thenReturn(new com.isim.dims.dto.TagRef("tag-1", "高速", null));

        stubStatsSceneSuccess();

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        verify(sensorStreamMapper, never()).insert(any(SensorStream.class));
        verify(dataSliceMapper, atLeastOnce()).updateById(any(DataSlice.class));
    }

    @Test
    void processParsePipeline_InvalidTagIds_SkipsNonExistentTags() {
        IngestionTask task = new IngestionTask();
        task.setId("task-1");
        task.setSliceId("slice-1");
        task.setStatus("UPLOADING");

        when(ingestionTaskMapper.selectOne(any())).thenReturn(task);
        // Simulate IngestionService.updateTaskStatus to modify task in-place and trigger updateById
        doAnswer(invocation -> {
            IngestionTask t = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            int progress = invocation.getArgument(2);
            t.setStatus(status);
            t.setProgress(progress);
            ingestionTaskMapper.updateById(t);
            return null;
        }).when(ingestionService).updateTaskStatus(any(IngestionTask.class), anyString(), anyInt());

        String parseResult = "{\"start_ts\":\"2026-07-24T09:00:00Z\",\"end_ts\":\"2026-07-24T09:30:00Z\",\"duration_ms\":30000,\"msg_count_total\":500,\"streams\":[]}";
        when(pythonExecutor.execute(eq("parse_rosbag.py"), anyMap(), eq(300)))
                .thenReturn(parseResult);

        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("UPLOADING");
        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        String tagResult = "{\"tag_ids\":[\"tag-valid\",\"tag-invalid\"]}";
        when(pythonExecutor.execute(eq("auto_tag.py"), anyMap(), eq(120)))
                .thenReturn(tagResult);

        // 第一个 tag_id 命中词表、第二个未命中 → 只应落一条关联
        when(simsTagCatalog.find(any())).thenReturn(new com.isim.dims.dto.TagRef("tag-1", "高速", null), null);

        stubStatsSceneSuccess();

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        verify(dataSliceTagMapper, times(1)).insert(argThat((DataSliceTag dst) ->
                dst.getTagId().equals("tag-valid") && "auto".equals(dst.getSource())
        ));
    }

    @Test
    void processParsePipeline_TaskNotFound_LogsAndReturns() {
        when(ingestionTaskMapper.selectOne(any())).thenReturn(null);

        pipelineService.processParsePipeline("slice-1", "/data/bags/slice-1/original.bag");

        verify(pythonExecutor, never()).execute(anyString(), anyMap());
        verify(dataSliceMapper, never()).updateById(any(DataSlice.class));
    }
}
