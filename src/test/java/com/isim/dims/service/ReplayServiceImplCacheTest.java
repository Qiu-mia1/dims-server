package com.isim.dims.service;

import com.isim.dims.config.StorageConfig;
import com.isim.dims.dto.MapFrameResponse;
import com.isim.dims.dto.ObstacleFrameResponse;
import com.isim.dims.dto.ObstacleQueryResult;
import com.isim.dims.dto.PerceptionFrameResponse;
import com.isim.dims.dto.RawMessagePageResponse;
import com.isim.dims.dto.ReplayExtractionRequest;
import com.isim.dims.dto.ReplayExtractionResponse;
import com.isim.dims.dto.ReplayFrameQueryResult;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.impl.LocalStorageService;
import com.isim.dims.service.impl.ReplayServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回放 Service 缓存读取测试
 *
 * <p>夹具结构与 dims-python 脚本真实产物一致（以 7c6c3055 切片真实提取结果为模板）：
 * index.json 对象含 segments[]；分段文件为对象含 frames[]；帧含 stamp_ns/calibrated/obstacles；
 * 障碍目标字段与契约 ObstacleTarget 一致；消息 JSONL 每行 { seq, stamp_ns, stamp, data }。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReplayServiceImplCacheTest {

    /** 与真实 bag 首条消息时间一致（2026-07-09T03:16:41.270771Z） */
    private static final long BASE_NS = 1783567001270770944L;
    private static final String BASE_RFC3339 = "2026-07-09T03:16:41.270771Z";

    @TempDir
    Path tempDir;

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private SensorStreamMapper sensorStreamMapper;

    @Mock
    private DataSliceTagMapper dataSliceTagMapper;

    @Mock
    private CalibrationService calibrationService;

    @Mock
    private ReplayExtractionTaskService replayExtractionTaskService;

    @Mock
    private StorageConfig storageConfig;

    @Mock
    private SimsTagCatalog simsTagCatalog;

    private ReplayServiceImpl replayService;

    private DataSlice slice;

    @BeforeEach
    void setUp() {
        lenient().when(storageConfig.getReplayCacheDir()).thenReturn(tempDir.toString());
        lenient().when(storageConfig.getVideoDir()).thenReturn(tempDir.resolve("videos").toString());

        // 存储门面：真实 LocalStorageService + StorageAccess（local 模式，key→目录映射到 tempDir），
        // fixture 落盘的真实文件经存储层读取，与生产语义一致
        LocalStorageService localStorage = new LocalStorageService(storageConfig);
        StorageAccess realAccess = new StorageAccess(localStorage, storageConfig, dataSliceMapper);
        replayService = new ReplayServiceImpl(dataSliceMapper, sensorStreamMapper,
                dataSliceTagMapper, calibrationService, replayExtractionTaskService, realAccess, simsTagCatalog);

        slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStartTs(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(BASE_NS / 1_000_000), ZoneOffset.UTC));
        slice.setEndTs(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(BASE_NS / 1_000_000 + 30_000), ZoneOffset.UTC));
        lenient().when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
    }

    // ========================================================================
    // 障碍缓存读取
    // ========================================================================

    @Test
    void obstacles_CacheHit_ReturnsFrameInWindow() throws Exception {
        writeObstacleFixture(2);

        // 请求 time = BASE + 400ms → 窗口 [200ms, 400ms] 内最近一帧 = 300ms 帧
        ObstacleQueryResult result = replayService.getObstacles("slice-1",
                fmt(BASE_NS + 400_000_000L), 200, "radar,ultrasonic");

        assertThat(result.getTaskResponse()).isNull();
        ObstacleFrameResponse frame = result.getFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.getTimeNs()).isEqualTo(BASE_NS + 300_000_000L);
        assertThat(frame.getFrame()).isEqualTo("ego");
        assertThat(frame.getUnit()).isEqualTo("m");
        assertThat(frame.isCalibrated()).isFalse();
        assertThat(frame.getObstacles()).hasSize(2);
    }

    @Test
    void obstacles_CacheHit_FiltersBySensorTypes() throws Exception {
        writeObstacleFixture(2);

        ObstacleQueryResult result = replayService.getObstacles("slice-1",
                fmt(BASE_NS + 400_000_000L), 200, "radar");

        assertThat(result.getFrame().getObstacles())
                .allSatisfy(t -> assertThat(t.getType()).isEqualTo("radar"));
    }

    @Test
    void obstacles_TimeOutsideSegments_ReturnsEmptyFrameWithoutTask() throws Exception {
        // S1 防无限循环：index 存在且可解析，但请求时刻（base+15s）不在任何 segment 覆盖范围
        // （切片尾部数据间隙/时间越界）→ 返回空帧 200，不再触发提取
        writeObstacleFixture(2);

        ObstacleQueryResult result = replayService.getObstacles("slice-1",
                fmt(BASE_NS + 15_000_000_000L), 200, "radar,ultrasonic");

        assertThat(result.getTaskResponse()).isNull();
        assertThat(result.getFrame()).isNotNull();
        assertThat(result.getFrame().getObstacles()).isEmpty();
        assertThat(result.getFrame().getTimeNs()).isEqualTo(BASE_NS + 15_000_000_000L);
        verify(replayExtractionTaskService, never()).createTask(any(), any());
    }

    @Test
    void obstacles_EmptyIndex_ReturnsEmptyFrameWithoutTask() throws Exception {
        // S1 防无限循环：空索引（已提取过但 0 分段，segments:[]）→ 200 空帧且不再创建任务
        Path obstaclesDir = tempDir.resolve("slice-1/replay/obstacles");
        Files.createDirectories(obstaclesDir);
        Files.writeString(obstaclesDir.resolve("index.json"),
                "{\"calibrated\":true,\"start_ns\":" + BASE_NS + ",\"end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"segments\":[]}");

        ObstacleQueryResult result = replayService.getObstacles("slice-1",
                fmt(BASE_NS + 400_000_000L), 200, null);

        assertThat(result.getTaskResponse()).isNull();
        assertThat(result.getFrame()).isNotNull();
        assertThat(result.getFrame().getObstacles()).isEmpty();
        // W1：空帧 calibrated 继承 index 的实际值（此处 index.calibrated=true），不误报「标定缺失」
        assertThat(result.getFrame().isCalibrated()).isTrue();
        verify(replayExtractionTaskService, never()).createTask(any(), any());
    }

    @Test
    void obstacles_NoCache_TriggersExtractionWithSliceRange() {
        ReplayExtractionResponse taskResp = new ReplayExtractionResponse();
        taskResp.setTaskId("task-2");
        taskResp.setStatus("PENDING");
        when(replayExtractionTaskService.createTask(any(), any(ReplayExtractionRequest.class)))
                .thenReturn(taskResp);

        // 切片有雷达/超声波 topic
        SensorStream radar = new SensorStream();
        radar.setTopic("/perception/radar/result_front");
        radar.setSensorCategory("radar");
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of(radar));

        ObstacleQueryResult result = replayService.getObstacles("slice-1", BASE_RFC3339, 200, null);

        assertThat(result.getTaskResponse().getTaskId()).isEqualTo("task-2");
    }

    // ========================================================================
    // 感知缓存读取（read-through，frame=global）
    // ========================================================================

    @Test
    void perception_CacheHit_ReturnsFrameInWindow() throws Exception {
        writePerceptionFixture();

        // 请求 time = BASE + 400ms → 窗口 [200ms, 400ms] 内最近一帧 = 300ms 帧
        ReplayFrameQueryResult<PerceptionFrameResponse> result =
                replayService.getPerception("slice-1", fmt(BASE_NS + 400_000_000L), 200);

        assertThat(result.getTaskResponse()).isNull();
        PerceptionFrameResponse frame = result.getFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.getTimeNs()).isEqualTo(BASE_NS + 300_000_000L);
        assertThat(frame.getFrame()).isEqualTo("global");
        assertThat(frame.getUnit()).isEqualTo("m");
        assertThat(frame.getObjects()).hasSize(2);
        PerceptionFrameResponse.PerceptionObject obj = frame.getObjects().get(0);
        assertThat(obj.getTrackId()).isEqualTo(100L);
        assertThat(obj.getClazz()).isEqualTo("vehicle");
        assertThat(obj.getConfidence()).isEqualTo(0.95);
        assertThat(obj.getLengthM()).isEqualTo(4.8);
        assertThat(obj.getX()).isEqualTo(-278.5);
        assertThat(obj.getY()).isEqualTo(525.2);
        assertThat(obj.getVx()).isEqualTo(1.2);
        assertThat(obj.getHeadingYaw()).isEqualTo(1.23);
    }

    @Test
    void perception_SegmentStartTime_ReturnsNearestFrameAfterRequest() throws Exception {
        // 回归：请求时刻为整秒/段起点（数据首帧相位在其后，如真实 smooth_object 10Hz
        // 首帧在段起点 +158ms），旧逻辑只向过去找（stamp <= timeNs）→ 空帧；
        // 双向窗口应命中其后最近的帧（fixture 首帧在 BASE+100ms，差 100ms ≤ 200ms 窗口）
        writePerceptionFixture();

        ReplayFrameQueryResult<PerceptionFrameResponse> result =
                replayService.getPerception("slice-1", fmt(BASE_NS), 200);

        assertThat(result.getTaskResponse()).isNull();
        PerceptionFrameResponse frame = result.getFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.getTimeNs()).isEqualTo(BASE_NS + 100_000_000L);
        assertThat(frame.getObjects()).hasSize(2);
    }

    @Test
    void perception_NoCache_TriggersExtractionWithPerceptionTopics() {
        ReplayExtractionResponse taskResp = new ReplayExtractionResponse();
        taskResp.setTaskId("task-perc-1");
        taskResp.setStatus("PENDING");
        when(replayExtractionTaskService.createTask(any(), any(ReplayExtractionRequest.class)))
                .thenReturn(taskResp);

        // 切片有感知 topic（/momenta_public/perception/*）
        SensorStream perc = new SensorStream();
        perc.setTopic("/momenta_public/perception/fusion/smooth_object");
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of(perc));

        ReplayFrameQueryResult<PerceptionFrameResponse> result =
                replayService.getPerception("slice-1", BASE_RFC3339, 200);

        assertThat(result.getFrame()).isNull();
        assertThat(result.getTaskResponse().getTaskId()).isEqualTo("task-perc-1");
        ArgumentCaptor<ReplayExtractionRequest> captor = ArgumentCaptor.forClass(ReplayExtractionRequest.class);
        verify(replayExtractionTaskService).createTask(eq("slice-1"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("perception");
        assertThat(captor.getValue().getTopics())
                .containsExactly("/momenta_public/perception/fusion/smooth_object");
    }

    @Test
    void perception_NoPerceptionTopics_ReturnsEmptyFrameWithoutTask() {
        // 无缓存 + 切片无感知 topic → 200 空帧（frame=global），不创建任务
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of());

        ReplayFrameQueryResult<PerceptionFrameResponse> result =
                replayService.getPerception("slice-1", BASE_RFC3339, 200);

        assertThat(result.getTaskResponse()).isNull();
        assertThat(result.getFrame()).isNotNull();
        assertThat(result.getFrame().getObjects()).isEmpty();
        assertThat(result.getFrame().getFrame()).isEqualTo("global");
        verify(replayExtractionTaskService, never()).createTask(any(), any());
    }

    @Test
    void perception_EmptyIndex_ReturnsEmptyFrameWithoutTask() throws Exception {
        // S1 防无限循环：已提取过但空索引（segments:[]）→ 200 空帧且不再创建任务
        Path perceptionDir = tempDir.resolve("slice-1/replay/perception");
        Files.createDirectories(perceptionDir);
        Files.writeString(perceptionDir.resolve("index.json"),
                "{\"frame\":\"global\",\"unit\":\"m\",\"start_ns\":" + BASE_NS
                        + ",\"end_ns\":" + (BASE_NS + 10_000_000_000L) + ",\"segments\":[]}");

        ReplayFrameQueryResult<PerceptionFrameResponse> result =
                replayService.getPerception("slice-1", fmt(BASE_NS + 400_000_000L), 200);

        assertThat(result.getTaskResponse()).isNull();
        assertThat(result.getFrame()).isNotNull();
        assertThat(result.getFrame().getObjects()).isEmpty();
        assertThat(result.getFrame().getTimeNs()).isEqualTo(BASE_NS + 400_000_000L);
        verify(replayExtractionTaskService, never()).createTask(any(), any());
    }

    // ========================================================================
    // 地图缓存读取（read-through，frame=ego）
    // ========================================================================

    @Test
    void map_CacheHit_ReturnsFrameInWindow() throws Exception {
        writeMapFixture();

        // 请求 time = BASE + 400ms → 窗口 [200ms, 400ms] 内最近一帧 = 300ms 帧
        ReplayFrameQueryResult<MapFrameResponse> result =
                replayService.getMap("slice-1", fmt(BASE_NS + 400_000_000L), 200);

        assertThat(result.getTaskResponse()).isNull();
        MapFrameResponse frame = result.getFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.getTimeNs()).isEqualTo(BASE_NS + 300_000_000L);
        assertThat(frame.getFrame()).isEqualTo("ego");
        assertThat(frame.getUnit()).isEqualTo("m");
        assertThat(frame.getLaneBoundaries()).hasSize(1);
        MapFrameResponse.MapLine lane = frame.getLaneBoundaries().get(0);
        assertThat(lane.getType()).isEqualTo("dashed");
        assertThat(lane.getTypeValue()).isEqualTo(2);
        assertThat(lane.getColor()).isEqualTo("white");
        assertThat(lane.getColorValue()).isEqualTo(1);
        assertThat(lane.getLineWidthM()).isEqualTo(0.15);
        assertThat(lane.getPoints()).hasSize(2);
        assertThat(lane.getPoints().get(0).getX()).isEqualTo(1.5);
        assertThat(frame.getRoadBoundaries()).hasSize(1);
        assertThat(frame.getRoadBoundaries().get(0).getType()).isEqualTo("normal");
        assertThat(frame.getStopLines()).hasSize(1);
        assertThat(frame.getStopLines().get(0).getPoints()).hasSize(2);
    }

    @Test
    void map_NoCache_TriggersExtractionWithMapTopics() {
        ReplayExtractionResponse taskResp = new ReplayExtractionResponse();
        taskResp.setTaskId("task-map-1");
        taskResp.setStatus("PENDING");
        when(replayExtractionTaskService.createTask(any(), any(ReplayExtractionRequest.class)))
                .thenReturn(taskResp);

        // 切片有地图 topic（/momenta_public/ddld/*）
        SensorStream ddld = new SensorStream();
        ddld.setTopic("/momenta_public/ddld/landmark_vis");
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of(ddld));

        ReplayFrameQueryResult<MapFrameResponse> result =
                replayService.getMap("slice-1", BASE_RFC3339, 200);

        assertThat(result.getFrame()).isNull();
        assertThat(result.getTaskResponse().getTaskId()).isEqualTo("task-map-1");
        ArgumentCaptor<ReplayExtractionRequest> captor = ArgumentCaptor.forClass(ReplayExtractionRequest.class);
        verify(replayExtractionTaskService).createTask(eq("slice-1"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("map");
        assertThat(captor.getValue().getTopics()).containsExactly("/momenta_public/ddld/landmark_vis");
    }

    @Test
    void map_NoMapTopics_ReturnsEmptyFrameWithoutTask() {
        // 无缓存 + 切片无地图 topic → 200 空帧（frame=ego），不创建任务
        when(sensorStreamMapper.selectList(any())).thenReturn(List.of());

        ReplayFrameQueryResult<MapFrameResponse> result =
                replayService.getMap("slice-1", BASE_RFC3339, 200);

        assertThat(result.getTaskResponse()).isNull();
        assertThat(result.getFrame()).isNotNull();
        assertThat(result.getFrame().getLaneBoundaries()).isEmpty();
        assertThat(result.getFrame().getRoadBoundaries()).isEmpty();
        assertThat(result.getFrame().getStopLines()).isEmpty();
        assertThat(result.getFrame().getFrame()).isEqualTo("ego");
        verify(replayExtractionTaskService, never()).createTask(any(), any());
    }

    @Test
    void map_TimeOutsideSegments_ReturnsEmptyFrameWithoutTask() throws Exception {
        // S1 防无限循环：index 存在且可解析，但请求时刻不在任何 segment 覆盖范围 → 200 空帧
        writeMapFixture();

        ReplayFrameQueryResult<MapFrameResponse> result =
                replayService.getMap("slice-1", fmt(BASE_NS + 15_000_000_000L), 200);

        assertThat(result.getTaskResponse()).isNull();
        assertThat(result.getFrame()).isNotNull();
        assertThat(result.getFrame().getLaneBoundaries()).isEmpty();
        assertThat(result.getFrame().getTimeNs()).isEqualTo(BASE_NS + 15_000_000_000L);
        verify(replayExtractionTaskService, never()).createTask(any(), any());
    }

    // ========================================================================
    // 消息缓存读取（分页 + seq + next_offset）
    // ========================================================================

    @Test
    void messages_PaginatesAcrossFiles() throws Exception {
        long s1 = BASE_NS;
        long s2 = s1 + 600_000_000L; // 60 条 × 10ms
        long s3 = s2 + 600_000_000L;
        writeMessageFixture(s1, s2, s3);

        SensorStream stream = new SensorStream();
        stream.setTopic("/perception/radar/result_front");
        stream.setMsgType("perception_interface_msgs/msg/RadarPerceptionResult");
        when(sensorStreamMapper.selectOne(any())).thenReturn(stream);

        // 第一页：seq 1..50，next=50
        RawMessagePageResponse page1 = replayService.getMessages("slice-1",
                "/perception/radar/result_front", fmt(s1), fmt(s3), 0, 50);
        assertThat(page1.getTotal()).isEqualTo(120);
        assertThat(page1.getMessages()).hasSize(50);
        assertThat(page1.getMessages().get(0).getSeq()).isEqualTo(1);
        assertThat(page1.getMessages().get(49).getSeq()).isEqualTo(50);
        assertThat(page1.getNextOffset()).isEqualTo(50);

        // 跨文件第二页：seq 51..100，next=100
        RawMessagePageResponse page2 = replayService.getMessages("slice-1",
                "/perception/radar/result_front", fmt(s1), fmt(s3), 50, 50);
        assertThat(page2.getMessages()).hasSize(50);
        assertThat(page2.getMessages().get(0).getSeq()).isEqualTo(51);
        assertThat(page2.getNextOffset()).isEqualTo(100);

        // 末页：seq 111..120，next=null
        RawMessagePageResponse page3 = replayService.getMessages("slice-1",
                "/perception/radar/result_front", fmt(s1), fmt(s3), 110, 20);
        assertThat(page3.getMessages()).hasSize(10);
        assertThat(page3.getMessages().get(0).getSeq()).isEqualTo(111);
        assertThat(page3.getNextOffset()).isNull();
    }

    @Test
    void messages_PartialWindowInFile_CountsCorrectly() throws Exception {
        // 只请求第 2 个文件的中间窗口（[s2+300ms, s2+600ms]）
        long s1 = BASE_NS;
        long s2 = s1 + 600_000_000L;
        long s3 = s2 + 600_000_000L;
        writeMessageFixture(s1, s2, s3);

        SensorStream stream = new SensorStream();
        stream.setTopic("/perception/radar/result_front");
        stream.setMsgType("perception_interface_msgs/msg/RadarPerceptionResult");
        when(sensorStreamMapper.selectOne(any())).thenReturn(stream);

        RawMessagePageResponse page = replayService.getMessages("slice-1",
                "/perception/radar/result_front", fmt(s2 + 300_000_000L), fmt(s2 + 600_000_000L), 0, 200);

        assertThat(page.getTotal()).isEqualTo(30);
        assertThat(page.getMessages()).hasSize(30);
        assertThat(page.getMessages().get(0).getSeq()).isEqualTo(1);
        assertThat(page.getNextOffset()).isNull();
    }

    @Test
    void messages_NoCache_ReturnsEmptyPage() {
        SensorStream stream = new SensorStream();
        stream.setTopic("/perception/radar/result_front");
        stream.setMsgType("perception_interface_msgs/msg/RadarPerceptionResult");
        when(sensorStreamMapper.selectOne(any())).thenReturn(stream);

        RawMessagePageResponse page = replayService.getMessages("slice-1",
                "/perception/radar/result_front", fmt(BASE_NS), fmt(BASE_NS + 1_000_000_000L), 0, 50);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getMessages()).isEmpty();
        assertThat(page.getNextOffset()).isNull();
    }

    // ========================================================================
    // 夹具
    // ========================================================================

    /** 写障碍缓存夹具：3 帧（100ms/200ms/300ms），每帧 2 目标（radar + ultrasonic） */
    private void writeObstacleFixture(int obstaclesPerFrame) throws Exception {
        Path obstaclesDir = tempDir.resolve("slice-1/replay/obstacles");
        Files.createDirectories(obstaclesDir.resolve("segments"));

        StringBuilder frames = new StringBuilder("[");
        for (int i = 1; i <= 3; i++) {
            if (i > 1) {
                frames.append(",");
            }
            frames.append("{\"stamp_ns\":").append(BASE_NS + i * 100_000_000L)
                    .append(",\"stamp\":\"2026-07-09T03:16:41.").append(i).append("00Z\"")
                    .append(",\"calibrated\":false,\"obstacles\":[")
                    .append("{\"sensor\":\"Bosch_mrr_Front\",\"type\":\"radar\",\"id\":\"1\",")
                    .append("\"x\":12.3,\"y\":-1.2,\"vx\":0.5,\"vy\":0.1,\"status\":\"valid\"},")
                    .append("{\"sensor\":\"ultrasonic\",\"type\":\"ultrasonic\",\"id\":\"2\",")
                    .append("\"x\":1.8,\"y\":0.0,\"range_m\":1.8,\"status\":\"valid\"}]}");
        }
        frames.append("]");

        Files.writeString(obstaclesDir.resolve("segments/" + BASE_NS + ".json"),
                "{\"segment_start_ns\":" + BASE_NS + ",\"segment_end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"frame\":\"ego\",\"unit\":\"m\",\"calibrated\":false,\"frames\":" + frames + "}");
        Files.writeString(obstaclesDir.resolve("index.json"),
                "{\"calibrated\":false,\"start_ns\":" + BASE_NS + ",\"end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"segments\":[{\"segment_start_ns\":" + BASE_NS
                        + ",\"segment_end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"file\":\"segments/" + BASE_NS + ".json\",\"frame_count\":3}]}");
    }

    /**
     * 写感知缓存夹具：3 帧（100ms/200ms/300ms），每帧 2 目标（global 系），
     * 布局与 extract_perception.py 真实产物一致（index.json 含 segments[]，
     * 分段文件为对象含 frames[]，帧含 time_ns/objects）
     */
    private void writePerceptionFixture() throws Exception {
        Path perceptionDir = tempDir.resolve("slice-1/replay/perception");
        Files.createDirectories(perceptionDir.resolve("segments"));

        StringBuilder frames = new StringBuilder("[");
        for (int i = 1; i <= 3; i++) {
            if (i > 1) {
                frames.append(",");
            }
            long ns = BASE_NS + i * 100_000_000L;
            frames.append("{\"time_ns\":").append(ns)
                    .append(",\"time\":\"").append(fmt(ns)).append("\"")
                    .append(",\"frame\":\"global\",\"unit\":\"m\",\"objects\":[")
                    .append("{\"track_id\":100,\"class\":\"vehicle\",\"confidence\":0.95,")
                    .append("\"length_m\":4.8,\"width_m\":1.9,\"height_m\":1.5,")
                    .append("\"x\":-278.5,\"y\":525.2,\"z\":0.0,\"vx\":1.2,\"vy\":0.3,\"heading_yaw\":1.23},")
                    .append("{\"track_id\":101,\"class\":\"vru\",\"confidence\":0.87,")
                    .append("\"length_m\":0.5,\"width_m\":0.4,\"height_m\":1.7,")
                    .append("\"x\":-270.1,\"y\":530.8,\"z\":0.0,\"vx\":-0.4,\"vy\":0.1,\"heading_yaw\":-2.1}]}");
        }
        frames.append("]");

        Files.writeString(perceptionDir.resolve("segments/" + BASE_NS + ".json"),
                "{\"segment_start_ns\":" + BASE_NS + ",\"segment_end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"frame\":\"global\",\"unit\":\"m\",\"frames\":" + frames + "}");
        Files.writeString(perceptionDir.resolve("index.json"),
                "{\"frame\":\"global\",\"unit\":\"m\",\"start_ns\":" + BASE_NS
                        + ",\"end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"segments\":[{\"segment_start_ns\":" + BASE_NS
                        + ",\"segment_end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"file\":\"segments/" + BASE_NS + ".json\",\"frame_count\":3}]}");
    }

    /**
     * 写地图缓存夹具：3 帧（100ms/200ms/300ms），每帧含 1 车道线 + 1 道路边界 + 1 停止线
     * （ego 系），布局与 extract_map.py 真实产物一致
     */
    private void writeMapFixture() throws Exception {
        Path mapDir = tempDir.resolve("slice-1/replay/map");
        Files.createDirectories(mapDir.resolve("segments"));

        StringBuilder frames = new StringBuilder("[");
        for (int i = 1; i <= 3; i++) {
            if (i > 1) {
                frames.append(",");
            }
            long ns = BASE_NS + i * 100_000_000L;
            frames.append("{\"time_ns\":").append(ns)
                    .append(",\"time\":\"").append(fmt(ns)).append("\"")
                    .append(",\"frame\":\"ego\",\"unit\":\"m\"")
                    .append(",\"lane_boundaries\":[{\"type\":\"dashed\",\"type_value\":2,")
                    .append("\"color\":\"white\",\"color_value\":1,\"line_width_m\":0.15,")
                    .append("\"points\":[{\"x\":1.5,\"y\":-1.8},{\"x\":2.0,\"y\":-1.8}]}],")
                    .append("\"road_boundaries\":[{\"type\":\"normal\",\"type_value\":2,")
                    .append("\"points\":[{\"x\":-50.0,\"y\":32.0},{\"x\":110.0,\"y\":32.0}]}],")
                    .append("\"stop_lines\":[{\"type\":\"normal\",\"type_value\":1,")
                    .append("\"points\":[{\"x\":5.0,\"y\":-3.0},{\"x\":5.0,\"y\":3.0}]}]}");
        }
        frames.append("]");

        Files.writeString(mapDir.resolve("segments/" + BASE_NS + ".json"),
                "{\"segment_start_ns\":" + BASE_NS + ",\"segment_end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"frame\":\"ego\",\"unit\":\"m\",\"frames\":" + frames + "}");
        Files.writeString(mapDir.resolve("index.json"),
                "{\"frame\":\"ego\",\"unit\":\"m\",\"start_ns\":" + BASE_NS
                        + ",\"end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"segments\":[{\"segment_start_ns\":" + BASE_NS
                        + ",\"segment_end_ns\":" + (BASE_NS + 10_000_000_000L)
                        + ",\"file\":\"segments/" + BASE_NS + ".json\",\"frame_count\":3}]}");
    }

    /** 写消息缓存夹具：两个 JSONL 文件各 60 条（10ms 间隔） */
    private void writeMessageFixture(long s1, long s2, long s3) throws Exception {
        String topicSafe = "_perception_radar_result_front";
        Path topicDir = tempDir.resolve("slice-1/replay/messages/" + topicSafe);
        Files.createDirectories(topicDir);

        StringBuilder lines1 = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            long ns = s1 + i * 10_000_000L;
            lines1.append("{\"seq\":").append(i + 1).append(",\"stamp_ns\":").append(ns)
                    .append(",\"stamp\":\"").append(fmt(ns)).append("\",\"data\":{\"v\":").append(i).append("}}\n");
        }
        StringBuilder lines2 = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            long ns = s2 + i * 10_000_000L;
            lines2.append("{\"seq\":").append(i + 1).append(",\"stamp_ns\":").append(ns)
                    .append(",\"stamp\":\"").append(fmt(ns)).append("\",\"data\":{\"v\":").append(i + 60).append("}}\n");
        }
        Files.writeString(topicDir.resolve("data_" + s1 + ".jsonl"), lines1.toString());
        Files.writeString(topicDir.resolve("data_" + s2 + ".jsonl"), lines2.toString());
        Files.writeString(topicDir.resolve("index.json"),
                "{\"topic\":\"/perception/radar/result_front\","
                        + "\"type\":\"perception_interface_msgs/msg/RadarPerceptionResult\","
                        + "\"total\":120,\"start_ns\":" + s1 + ",\"end_ns\":" + s3 + ",\"files\":["
                        + "{\"start_ns\":" + s1 + ",\"end_ns\":" + s2 + ",\"file\":\"data_" + s1 + ".jsonl\",\"count\":60},"
                        + "{\"start_ns\":" + s2 + ",\"end_ns\":" + s3 + ",\"file\":\"data_" + s2 + ".jsonl\",\"count\":60}]}");
    }

    private String fmt(long ns) {
        long seconds = Math.floorDiv(ns, 1_000_000_000L);
        int nanos = (int) Math.floorMod(ns, 1_000_000_000L);
        return java.time.Instant.ofEpochSecond(seconds, nanos).toString();
    }
}
