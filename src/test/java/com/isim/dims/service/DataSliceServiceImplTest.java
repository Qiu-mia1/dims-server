package com.isim.dims.service;

import com.isim.dims.exception.StorageException;
import com.isim.dims.dto.SliceDetailResponse;
import com.isim.dims.dto.SliceListResponse;
import com.isim.dims.dto.SliceSearchRequest;
import com.isim.dims.dto.TagRef;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.DataSliceTag;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.IngestionTaskStatusHistory;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.CfdiMetaMapper;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.IngestionTaskStatusHistoryMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.mapper.SliceStatsMapper;
import com.isim.dims.service.impl.DataSliceServiceImpl;
import com.isim.dims.service.impl.StorageAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataSliceServiceImplTest {

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private SensorStreamMapper sensorStreamMapper;

    @Mock
    private DataSliceTagMapper dataSliceTagMapper;

    @Mock
    private IngestionTaskMapper ingestionTaskMapper;

    @Mock
    private IngestionTaskStatusHistoryMapper ingestionTaskStatusHistoryMapper;

    @Mock
    private SimsTagCatalog simsTagCatalog;

    @Mock
    private SliceStatsMapper sliceStatsMapper;

    @Mock
    private CfdiMetaMapper cfdiMetaMapper;

    @Mock
    private IngestionPipelineService ingestionPipelineService;

    @Mock
    private StorageAccess storageAccess;

    @InjectMocks
    private DataSliceServiceImpl dataSliceService;

    @TempDir
    Path tempDir;

    @Captor
    private ArgumentCaptor<DataSlice> dataSliceCaptor;

    @Captor
    private ArgumentCaptor<IngestionTask> taskCaptor;

    @BeforeEach
    void setUp() {
        // 存储门面 mock：镜像 LocalStorageService 的 key→目录映射语义
        // （key "slices/{id}/original.bag" → {tempDir}/{id}/original.bag）
        lenient().when(storageAccess.isMinioMode()).thenReturn(false);
        lenient().doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            InputStream in = invocation.getArgument(1);
            Path target = tempDir.resolve(key.substring(key.indexOf('/') + 1));
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return null;
        }).when(storageAccess).put(anyString(), any(InputStream.class), anyLong(), anyString());
        // delete：删除文件并清理空父目录（镜像旧实现"删除切片目录"语义）
        lenient().doAnswer(invocation -> {
            Path p = Path.of((String) invocation.getArgument(0));
            Files.deleteIfExists(p);
            if (p.getParent() != null) {
                Files.deleteIfExists(p.getParent());
            }
            return null;
        }).when(storageAccess).delete(anyString());
        // getDetail 现在无条件查询 cfdi_meta 判定是否 CFDI 流水线，默认无记录 → null
        lenient().when(cfdiMetaMapper.selectOne(any())).thenReturn(null);
    }

    // ============ upload() ============

    @Test
    void upload_Success_SavesFileAndCreatesRecords() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "route_001.bag", "application/octet-stream", "some bag content".getBytes());

        when(dataSliceMapper.insert(any(DataSlice.class))).thenReturn(1);
        when(ingestionTaskMapper.insert(any(IngestionTask.class))).thenReturn(1);
        when(ingestionTaskStatusHistoryMapper.insert(any(IngestionTaskStatusHistory.class))).thenReturn(1);

        Map<String, String> result = dataSliceService.upload(file, null, "MMT");

        // Verify response
        assertThat(result)
                .containsKey("slice_id")
                .containsKey("task_id")
                .containsEntry("status", "UPLOADING");

        // Verify file saved to disk（local 模式：key "slices/{id}/original.bag" → bagDir/{id}/original.bag）
        String sliceId = result.get("slice_id");
        Path bagPath = tempDir.resolve(sliceId).resolve("original.bag");
        assertTrue(Files.exists(bagPath));
        assertEquals("some bag content", Files.readString(bagPath));

        // Verify DataSlice record（file_path 语义升级为存储 key，非绝对路径）
        verify(dataSliceMapper).insert(dataSliceCaptor.capture());
        DataSlice savedSlice = dataSliceCaptor.getValue();
        assertEquals(sliceId, savedSlice.getId());
        assertEquals("route_001", savedSlice.getName());
        assertEquals("slices/" + sliceId + "/original.bag", savedSlice.getFilePath());
        assertEquals("LOCAL", savedSlice.getStorageType());
        assertEquals(16L, savedSlice.getFileSize());
        assertEquals("UPLOADING", savedSlice.getStatus());
        assertEquals("MMT", savedSlice.getSourceType());

        // Verify IngestionTask record
        verify(ingestionTaskMapper).insert(taskCaptor.capture());
        IngestionTask savedTask = taskCaptor.getValue();
        assertEquals(result.get("task_id"), savedTask.getId());
        assertEquals(sliceId, savedTask.getSliceId());
        assertEquals("UPLOADING", savedTask.getStatus());
        assertEquals(0, savedTask.getProgress());

        // Verify async pipeline invoked（传入存储 key）
        verify(ingestionPipelineService).processParsePipeline(sliceId, "slices/" + sliceId + "/original.bag");
    }

    @Test
    void upload_WithCustomName_UsesProvidedName() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "route_001.bag", "application/octet-stream", "data".getBytes());

        when(dataSliceMapper.insert(any(DataSlice.class))).thenReturn(1);
        when(ingestionTaskMapper.insert(any(IngestionTask.class))).thenReturn(1);
        when(ingestionTaskStatusHistoryMapper.insert(any(IngestionTaskStatusHistory.class))).thenReturn(1);

        Map<String, String> result = dataSliceService.upload(file, "My Custom Route", "ZYT");

        // Verify DataSlice record captured
        verify(dataSliceMapper).insert(dataSliceCaptor.capture());
        DataSlice slice = dataSliceCaptor.getValue();
        assertEquals("My Custom Route", slice.getName());
    }

    @Test
    void upload_FileSaveFailure_ThrowsException() {
        // 存储写入失败 → UPLOAD_FAILED（覆盖 setUp 的 lenient put 落盘行为）
        doThrow(new StorageException("UPLOAD_FAILED: mock write error"))
                .when(storageAccess).put(anyString(), any(InputStream.class), anyLong(), anyString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.bag", "application/octet-stream", "data".getBytes());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> dataSliceService.upload(file, null, "MMT"));
        assertThat(exception.getMessage()).contains("UPLOAD_FAILED");

        verify(dataSliceMapper, never()).insert(any(DataSlice.class));
        verify(ingestionTaskMapper, never()).insert(any(IngestionTask.class));
        verify(ingestionPipelineService, never()).processParsePipeline(anyString(), anyString());
    }

    // ============ search() ============

    @Test
    void search_NoFilters_ReturnsAllResults() {
        SliceSearchRequest request = new SliceSearchRequest();

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("test");
        ds.setStatus("READY");
        ds.setCreatedAt(LocalDateTime.now());

        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        SliceListResponse response = dataSliceService.search(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getId()).isEqualTo("slice-1");
        assertThat(response.getTotal()).isEqualTo(1L);
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(50);
    }

    @Test
    void search_WithTimeRange_FiltersCorrectly() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setStartTs("2026-07-24T09:00:00Z");
        request.setEndTs("2026-07-24T10:00:00Z");
        request.setStatus("READY");

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("test");
        ds.setStatus("READY");
        ds.setCreatedAt(LocalDateTime.now());

        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        SliceListResponse response = dataSliceService.search(request);

        assertThat(response.getItems()).hasSize(1);
        // Verify the request was modified: RFC3339 → MySQL DATETIME format
        assertThat(request.getStartTs()).doesNotContain("T");
        assertThat(request.getStartTs()).contains(" ");
        assertThat(request.getEndTs()).doesNotContain("T");
    }

    @Test
    void search_WithGpsRadius_ConvertsToBbox() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setLat(31.2);
        request.setLon(121.4);
        request.setRadius(1000);

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("test");
        ds.setCreatedAt(LocalDateTime.now());

        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        dataSliceService.search(request);

        // Verify GPS center → bbox conversion
        assertThat(request.getMinLat()).isNotNull();
        assertThat(request.getMaxLat()).isNotNull();
        assertThat(request.getMinLon()).isNotNull();
        assertThat(request.getMaxLon()).isNotNull();
        assertThat(request.getMinLat()).isLessThan(31.2);
        assertThat(request.getMaxLat()).isGreaterThan(31.2);
    }

    @Test
    void search_WithTriggerTimeSort_ValidatesSortStringAndMapsTriggerTime() {
        SliceSearchRequest request = new SliceSearchRequest();
        // 触发时间排序（从近往远 = DESC）：白名单需放行 trigger_time
        request.setSort("trigger_time:desc");

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("test");
        // toSliceItem 在无 slice_stats.vehicle_type 时回退解析切片名，这里直接给出车型避免走回退分支
        ds.setVehicleType("CX835ICA");
        ds.setCreatedAt(LocalDateTime.now());
        // data_slice.trigger_time 存 UTC（meta.trigger_timestamp_us + utc_diff_us 换算结果）
        ds.setTriggerTime(LocalDateTime.of(2026, 7, 9, 1, 30, 36));

        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        SliceListResponse response = dataSliceService.search(request);

        ArgumentCaptor<SliceSearchRequest> captor = ArgumentCaptor.forClass(SliceSearchRequest.class);
        verify(dataSliceMapper).searchSlices(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo("trigger_time DESC");

        // 出参为 UTC RFC3339（前端按本地时区/北京时间展示）
        assertThat(response.getItems().get(0).getTriggerTime()).isEqualTo("2026-07-09T01:30:36Z");
    }

    @Test
    void search_WithSort_ValidatesSortString() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setSort("file_size:asc");

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setCreatedAt(LocalDateTime.now());

        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        dataSliceService.search(request);

        // Verify sort was validated and converted
        ArgumentCaptor<SliceSearchRequest> captor = ArgumentCaptor.forClass(SliceSearchRequest.class);
        verify(dataSliceMapper).searchSlices(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo("file_size ASC");
    }

    @Test
    void search_InvalidSort_ThrowsException() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setSort("malicious_column:desc");

        assertThrows(IllegalArgumentException.class,
                () -> dataSliceService.search(request));
        verify(dataSliceMapper, never()).searchSlices(any());
    }

    @Test
    void search_WithTags_IncludesTagsInItems() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setTags("tag-1,tag-2");

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("test");
        ds.setStatus("READY");
        ds.setCreatedAt(LocalDateTime.now());

        // 关联表只存 tag_id；名称由 SIMS 词表缓存解析（本地 tag 表已随 V29 移除）
        DataSliceTag row1 = new DataSliceTag();
        row1.setTagId("tag-1");
        DataSliceTag row2 = new DataSliceTag();
        row2.setTagId("tag-2");

        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of(row1, row2));
        when(simsTagCatalog.find("tag-1")).thenReturn(new TagRef("tag-1", "highway", "自定义标签 / 道路"));
        when(simsTagCatalog.find("tag-2")).thenReturn(new TagRef("tag-2", "daytime", "自定义标签 / 时间"));
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        SliceListResponse response = dataSliceService.search(request);

        assertThat(response.getItems().get(0).getTags())
                .extracting(TagRef::getName)
                .containsExactly("highway", "daytime");
    }

    @Test
    void search_EmptyResult_ReturnsEmptyList() {
        SliceSearchRequest request = new SliceSearchRequest();

        // count 为 0 时 search() 提前返回空列表，不会走到 searchSlices
        when(dataSliceMapper.countSlices(any())).thenReturn(0L);

        SliceListResponse response = dataSliceService.search(request);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotal()).isZero();
    }

    @Test
    void search_WithCfdiFilterParams_PassesToMapper() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setSourceType("MMT");
        request.setEventName("mid_highbeam");
        request.setCarType("CX835ICA");
        request.setProject("LUS");

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("cfdi-test");
        ds.setStatus("READY");
        ds.setCreatedAt(LocalDateTime.now());

        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        SliceListResponse response = dataSliceService.search(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getId()).isEqualTo("slice-1");

        // Verify the request params were passed through
        ArgumentCaptor<SliceSearchRequest> captor = ArgumentCaptor.forClass(SliceSearchRequest.class);
        verify(dataSliceMapper).searchSlices(captor.capture());
        SliceSearchRequest captured = captor.getValue();
        assertThat(captured.getSourceType()).isEqualTo("MMT");
        assertThat(captured.getEventName()).isEqualTo("mid_highbeam");
        assertThat(captured.getCarType()).isEqualTo("CX835ICA");
        assertThat(captured.getProject()).isEqualTo("LUS");
    }

    @Test
    void search_WithTagKeywordAndDateRange_PassesToMapper() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setTag("远光灯");
        request.setStartDate("2026-07-01T00:00:00Z");
        request.setEndDate("2026-07-31T23:59:59Z");

        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("test");
        ds.setStatus("READY");
        ds.setCreatedAt(LocalDateTime.now());

        // tag 表已随 V29 移除：关键词由 SimsTagCatalog 词表在内存里解析成 id 集合
        when(simsTagCatalog.all()).thenReturn(List.of(
                new TagRef("tag-light", "远光灯滥用", "自定义标签 / 事件"),
                new TagRef("tag-rain", "rainy", "自定义标签 / 天气")));
        when(dataSliceMapper.searchSlices(any())).thenReturn(List.of(ds));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        when(dataSliceMapper.countSlices(any())).thenReturn(1L);

        dataSliceService.search(request);

        ArgumentCaptor<SliceSearchRequest> captor = ArgumentCaptor.forClass(SliceSearchRequest.class);
        verify(dataSliceMapper).searchSlices(captor.capture());
        SliceSearchRequest captured = captor.getValue();

        // 关键词应被解析成 tag_id 集合（SQL 层按 id 过滤，不再 JOIN tag）
        assertThat(captured.getTagIds()).containsExactly("tag-light");
        // Date strings should be converted from RFC3339 to MySQL format
        assertThat(captured.getStartDate()).doesNotContain("T");
        assertThat(captured.getStartDate()).contains(" ");
        assertThat(captured.getEndDate()).doesNotContain("T");
    }

    /**
     * tag 表已随 V29 移除：关键词在 SIMS 词表里解析不到任何 id 时，
     * 不可能有切片满足该条件 → 必须短路返回空结果。
     * 既不能把条件静默丢掉（退化成「不过滤」返回全部切片），
     * 也不能把空集合传给 IN 子句（SQL 语法错误）。
     */
    @Test
    void search_TagKeyword_NoMatchInCatalog_ReturnsEmptyWithoutQuery() {
        SliceSearchRequest request = new SliceSearchRequest();
        request.setTag("nonexistent-tag");

        when(simsTagCatalog.all()).thenReturn(List.of(
                new TagRef("tag-rain", "rainy", "自定义标签 / 天气")));

        SliceListResponse response = dataSliceService.search(request);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotal()).isZero();
        verify(dataSliceMapper, never()).searchSlices(any());
        verify(dataSliceMapper, never()).countSlices(any());
    }

    // ============ getDetail() ============

    @Test
    void getDetail_Found_ReturnsDetailWithStreams() {
        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("detail-slice");
        ds.setFilePath("/data/bags/slice-1/original.bag");
        ds.setFileSize(1000L);
        ds.setDurationMs(30000L);
        ds.setStatus("READY");
        ds.setCreatedAt(LocalDateTime.now());

        SensorStream stream = new SensorStream();
        stream.setId("stream-1");
        stream.setSliceId("slice-1");
        stream.setTopic("/sensor/camera");
        stream.setSensorCategory("camera");
        stream.setMsgCount(500);
        stream.setFrequencyHz(30.0);

        DataSliceTag tagRow = new DataSliceTag();
        tagRow.setTagId("tag-1");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(ds);
        when(sensorStreamMapper.selectBySliceId("slice-1")).thenReturn(List.of(stream));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of(tagRow));
        when(simsTagCatalog.nameOf("tag-1")).thenReturn("highway");
        // slice_stats 1:1 无统计记录时为 null
        when(sliceStatsMapper.selectOne(any())).thenReturn(null);

        // 查询 task_id 时返回空（无任务）
        when(ingestionTaskMapper.selectOne(any())).thenReturn(null);

        SliceDetailResponse detail = dataSliceService.getDetail("slice-1");

        assertThat(detail.getId()).isEqualTo("slice-1");
        assertThat(detail.getName()).isEqualTo("detail-slice");
        assertThat(detail.getStatus()).isEqualTo("READY");
        assertThat(detail.getTags()).containsExactly("highway");
        assertThat(detail.getStreams()).hasSize(1);
        assertThat(detail.getStreams().get(0).getTopic()).isEqualTo("/sensor/camera");
        assertThat(detail.getDownloadUrl()).isEqualTo("/v1/data-slices/slice-1/exports");
        // task_id 为空时返回 null
        assertThat(detail.getTaskId()).isNull();
    }

    @Test
    void getDetail_WithTask_ReturnsTaskId() {
        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setName("detail-slice");
        ds.setFilePath("/data/bags/slice-1/original.bag");
        ds.setFileSize(1000L);
        ds.setDurationMs(30000L);
        ds.setStatus("READY");
        ds.setCreatedAt(LocalDateTime.now());

        SensorStream stream = new SensorStream();
        stream.setId("stream-1");
        stream.setSliceId("slice-1");
        stream.setTopic("/sensor/camera");
        stream.setSensorCategory("camera");

        IngestionTask existingTask = new IngestionTask();
        existingTask.setId("task-123");
        existingTask.setSliceId("slice-1");
        existingTask.setStatus("READY");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(ds);
        when(sensorStreamMapper.selectBySliceId("slice-1")).thenReturn(List.of(stream));
        when(dataSliceTagMapper.selectBySliceId("slice-1")).thenReturn(List.of());
        // slice_stats 1:1 无统计记录时为 null
        when(sliceStatsMapper.selectOne(any())).thenReturn(null);
        when(ingestionTaskMapper.selectOne(any())).thenReturn(existingTask);

        SliceDetailResponse detail = dataSliceService.getDetail("slice-1");

        assertThat(detail.getId()).isEqualTo("slice-1");
        assertThat(detail.getTaskId()).isEqualTo("task-123");
    }

    @Test
    void getDetail_NotFound_ThrowsException() {
        when(dataSliceMapper.selectById("invalid")).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> dataSliceService.getDetail("invalid"));
    }

    // ============ delete() ============

    @Test
    void delete_Success_DeletesFilesAndRecords() {
        Path sliceDir = tempDir.resolve("slice-to-delete");
        Path bagFile = sliceDir.resolve("original.bag");
        try {
            Files.createDirectories(sliceDir);
            Files.writeString(bagFile, "delete me");
        } catch (Exception e) {
            fail("Failed to setup test file: " + e.getMessage());
        }

        DataSlice ds = new DataSlice();
        ds.setId("slice-to-delete");
        ds.setFilePath(bagFile.toString());

        when(dataSliceMapper.selectById("slice-to-delete")).thenReturn(ds);

        dataSliceService.delete("slice-to-delete");

        // Verify file deleted
        assertFalse(Files.exists(bagFile));
        assertFalse(Files.exists(sliceDir));

        // Verify cascading deletes
        verify(sensorStreamMapper).delete(any());
        verify(dataSliceTagMapper).delete(any());
        verify(ingestionTaskMapper).delete(any());
        verify(dataSliceMapper).deleteById(eq("slice-to-delete"));
    }

    @Test
    void delete_NotFound_ThrowsException() {
        when(dataSliceMapper.selectById("invalid")).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> dataSliceService.delete("invalid"));

        verify(sensorStreamMapper, never()).delete(any());
        verify(dataSliceMapper, never()).deleteById(anyString());
    }

    @Test
    void delete_FileCleanupFailure_StillDeletesRecords() {
        DataSlice ds = new DataSlice();
        ds.setId("slice-1");
        ds.setFilePath("/nonexistent/path/original.bag");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(ds);

        // Should not throw even though file doesn't exist
        dataSliceService.delete("slice-1");

        // Record deletions still happen
        verify(sensorStreamMapper).delete(any());
        verify(dataSliceTagMapper).delete(any());
        verify(ingestionTaskMapper).delete(any());
        verify(dataSliceMapper).deleteById(eq("slice-1"));
    }

    // ============ prepareLogDownload() / writeLogBundle() ============
    //
    // 日志与 bag 不同源：bag 在对象存储，日志是导入流程落盘的会话目录，路径记在
    // data_slice.import_source_dir。目录形状取自现网导入会话（<session>_public/）：
    //   xxx.mfbag                                   ← 几百 MB 的包，必须被口径排除
    //   meta / upload.done / help/index.json.gz     ← 标记与辅助文件，必须排除
    //   log/camera_detection.log.1.gz               ← 目录段 log → 整目录收录
    //   public/<ts>_forever_log_<car>/calib_history.log.gz ← 旧夹具遗留（文件名 *.log.gz 仍命中）
    //   system.log                                  ← 文件名匹配 *.log → 收录

    /** 把 @Value 注入的字段显式设值：@InjectMocks 不会走 Spring 的 @Value 解析。 */
    private void configureLogDownload(String allowedRoots, long maxTotalBytes) {
        configureLogDownload(allowedRoots, maxTotalBytes, null, "log", "", "session");
    }

    private void configureLogDownload(String allowedRoots, long maxTotalBytes,
            String pathMappings, String dirNames, String dirNameContains) {
        configureLogDownload(allowedRoots, maxTotalBytes, pathMappings, dirNames, dirNameContains, "session");
    }

    private void configureLogDownload(String allowedRoots, long maxTotalBytes,
            String pathMappings, String dirNames, String dirNameContains, String logScope) {
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logAllowedRoots", allowedRoots);
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logMaxTotalBytes", maxTotalBytes);
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logPathMappings", pathMappings);
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logDirNames", dirNames);
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logDirNameContains", dirNameContains);
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logScope", logScope);
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logMetaNames", "meta,log_info.json");
        org.springframework.test.util.ReflectionTestUtils
                .setField(dataSliceService, "logSourceMode", "auto");
    }

    /** 造一个含 bag/元数据/两类日志的会话目录，返回目录路径。 */
    private Path buildSessionDir(String sliceId, String sessionName) throws Exception {
        Path session = tempDir.resolve(sliceId).resolve(sessionName);
        Files.createDirectories(session.resolve("log"));
        Files.createDirectories(session.resolve("help"));
        Files.createDirectories(session.resolve("public/1789520027000_forever_log_1699"));
        Files.write(session.resolve("xxx.mfbag"), new byte[1024]);
        Files.writeString(session.resolve("meta"), "{}");
        Files.writeString(session.resolve("upload.done"), "");
        Files.writeString(session.resolve("help/index.json.gz"), "help");
        Files.writeString(session.resolve("system.log"), "system log line\n");
        Files.writeString(session.resolve("log/camera_detection.log.1.gz"), "camera log");
        Files.writeString(session.resolve("public/1789520027000_forever_log_1699/calib_history.log.gz"), "calib");
        return session;
    }

    private DataSlice sliceWithLogDir(String sliceId, String name, Path dir) {
        return sliceWithLogDir(sliceId, name, dir, null, null);
    }

    private DataSlice sliceWithLogDir(String sliceId, String name, Path dir,
            String importMd5, String sessionId) {
        DataSlice ds = new DataSlice();
        ds.setId(sliceId);
        ds.setName(name);
        ds.setImportSourceDir(dir == null ? null : dir.toString());
        ds.setImportMd5(importMd5);
        ds.setSessionId(sessionId);
        return ds;
    }

    @Test
    void prepareLogDownload_CollectsOnlyLogFiles_AndZipsWhenMultiple() throws Exception {
        Path session = buildSessionDir("slice-1", "bb979705_20260908105427393933_public");
        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-1"))
                .thenReturn(sliceWithLogDir("slice-1", "road_brake_case", session));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-1");

        // bag / upload.done / help 不在口径内；meta 与 log/ 同级，属配套元数据 → 收录
        assertThat(download.files())
                .extracting(DataSliceService.LogDownload.LogFile::entryName)
                .containsExactlyInAnyOrder(
                        "log/camera_detection.log.1.gz",
                        "public/1789520027000_forever_log_1699/calib_history.log.gz",
                        "system.log",
                        "meta");
        assertThat(download.zip()).isTrue();
        assertThat(download.fileName()).isEqualTo("road_brake_case_logs.zip");
        assertThat(download.totalSize())
                .isEqualTo(Files.size(session.resolve("system.log"))
                        + Files.size(session.resolve("log/camera_detection.log.1.gz"))
                        + Files.size(session.resolve("public/1789520027000_forever_log_1699/calib_history.log.gz"))
                        + Files.size(session.resolve("meta")));
        // ZIP 长度不可预知，调用方不得把 totalSize 当响应长度用
        assertThat(download.totalSize()).isLessThan(Files.size(session.resolve("xxx.mfbag")));
    }

    @Test
    void prepareLogDownload_SingleLogFile_StreamsRawWithoutZip() throws Exception {
        Path session = tempDir.resolve("slice-2/sess_public");
        Files.createDirectories(session);
        Files.writeString(session.resolve("only.log"), "hello log");

        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-2"))
                .thenReturn(sliceWithLogDir("slice-2", "case", session));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-2");

        assertThat(download.zip()).isFalse();
        assertThat(download.fileName()).isEqualTo("only.log");

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        dataSliceService.writeLogBundle(download, out);
        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("hello log");
    }

    @Test
    void writeLogBundle_MultipleFiles_KeepsRelativePathsInsideZip() throws Exception {
        Path session = buildSessionDir("slice-3", "sess_public");
        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-3"))
                .thenReturn(sliceWithLogDir("slice-3", "case", session));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-3");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        dataSliceService.writeLogBundle(download, out);

        java.util.List<String> entries = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertThat(entries).containsExactlyInAnyOrder(
                "log/camera_detection.log.1.gz",
                "public/1789520027000_forever_log_1699/calib_history.log.gz",
                "system.log",
                "meta");
    }

    /** import_source_dir 为空 = 这批数据是直接上传的，压根没有源目录 → 必须有独立错误码。 */
    @Test
    void prepareLogDownload_BlankImportSourceDir_ThrowsLogPathMissing() {
        when(dataSliceMapper.selectById("slice-4"))
                .thenReturn(sliceWithLogDir("slice-4", "case", null));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataSliceService.prepareLogDownload("slice-4"));
        assertThat(ex.getMessage()).startsWith("LOG_PATH_MISSING");
    }

    @Test
    void prepareLogDownload_DirectoryMissing_ThrowsLogPathNotFound() {
        Path absent = tempDir.resolve("slice-5/never-downloaded_public");
        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-5"))
                .thenReturn(sliceWithLogDir("slice-5", "case", absent));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataSliceService.prepareLogDownload("slice-5"));
        assertThat(ex.getMessage()).startsWith("LOG_PATH_NOT_FOUND");
    }

    /** 白名单外的路径必须拒掉：脏数据不能把任意系统目录读出去。 */
    @Test
    void prepareLogDownload_DirectoryOutsideAllowedRoots_ThrowsLogPathNotAllowed() throws Exception {
        Path session = tempDir.resolve("slice-6/sess_public");
        Files.createDirectories(session);
        Files.writeString(session.resolve("a.log"), "x");

        configureLogDownload(tempDir.resolve("some-other-root").toString(), 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-6"))
                .thenReturn(sliceWithLogDir("slice-6", "case", session));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataSliceService.prepareLogDownload("slice-6"));
        assertThat(ex.getMessage()).startsWith("LOG_PATH_NOT_ALLOWED");
    }

    /**
     * 目录存在但没有日志 = 最可能踩到的坑（日志在同步环节被跳过），
     * 报错必须带上目录实际内容，否则用户只看到路径无从判断。
     */
    @Test
    void prepareLogDownload_NoLogFile_ThrowsLogNotFoundWithDirectoryPreview() throws Exception {
        Path session = tempDir.resolve("slice-7/sess_public");
        Files.createDirectories(session);
        Files.write(session.resolve("xxx.mfbag"), new byte[16]);
        Files.writeString(session.resolve("meta"), "{}");
        Files.createDirectories(session.resolve("public"));

        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-7"))
                .thenReturn(sliceWithLogDir("slice-7", "case", session));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataSliceService.prepareLogDownload("slice-7"));
        assertThat(ex.getMessage()).startsWith("LOG_NOT_FOUND");
        assertThat(ex.getMessage()).contains("xxx.mfbag").contains("meta").contains("public/");
    }

    @Test
    void prepareLogDownload_OversizedLogs_ThrowsLogTooLargeWithActualSize() throws Exception {
        Path session = tempDir.resolve("slice-8/sess_public");
        Files.createDirectories(session);
        Files.write(session.resolve("big.log"), new byte[100]);

        configureLogDownload(null, 10);
        when(dataSliceMapper.selectById("slice-8"))
                .thenReturn(sliceWithLogDir("slice-8", "case", session));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataSliceService.prepareLogDownload("slice-8"));
        assertThat(ex.getMessage()).startsWith("LOG_TOO_LARGE");
        // 报错里带上实际大小与上限，用户才知道「大多少」
        assertThat(ex.getMessage()).contains("100").contains("10");
    }

    @Test
    void prepareLogDownload_UnknownSlice_ThrowsSliceNotFound() {
        when(dataSliceMapper.selectById("nope")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataSliceService.prepareLogDownload("nope"));
        assertThat(ex.getMessage()).startsWith("SLICE_NOT_FOUND");
    }

    // ========================================================================
    // 库表路径 vs 磁盘形态 / 口径收窄 / 路径映射
    // ========================================================================

    private static final String REAL_MD5 = "096b61308e1923e99f59047cea236d24";
    private static final String REAL_SESSION = "ea236d24_20260831001436109984_public";
    /** 会话目录下的 bundle 目录（相对会话目录）：真实名形如 {@code <ts>_<ts>_30_<md5>}。 */
    private static final String BUNDLE =
            "public/1788106476109981_1788098561848972_30_" + REAL_MD5;

    /**
     * 按线上真实会话目录造一份对等结构的目录树（结构取自 NFS 上真实会话目录），
     * 返回会话目录。{@code dateSegment} 非空时插在 md5 段之前。
     *
     * <p><b>关键事实</b>：真实会话目录下是 {@code public/<bundle>/}，其中 {@code log/} 下全是
     * 日志压缩包（{@code *.log.<时间戳>.gz}），同级的 {@code meta} / {@code log_info.json} 是配套
     * 元数据，另有 {@code image/}、{@code help/} 等辅助目录与 {@code upload.done} 等簿记文件。
     * <b>没有</b> {@code logs/}、{@code forever_log} 之类目录 —— 日志只认 {@code log/} 这一处。
     *
     * <p>注意：真实日志文件名里带冒号（{@code fcl.log.1970-01-01_08:00:47.325898880.gz}），
     * Windows 上无法建这种文件，夹具改用等长的短横线形式 —— 形状（{@code *.log.<ts>.gz}）一致，
     * 正则匹配行为相同。
     */
    private Path buildRealSessionLayout(Path root, String dateSegment) throws Exception {
        Path base = root.resolve("mmt_test");
        if (dateSegment != null) {
            base = base.resolve(dateSegment);
        }
        Path session = base.resolve(REAL_MD5).resolve(REAL_SESSION);

        Path bundle = session.resolve(BUNDLE);
        Files.createDirectories(bundle.resolve("log"));
        Files.createDirectories(bundle.resolve("image"));
        Files.createDirectories(bundle.resolve("help"));
        Path log = bundle.resolve("log");
        Files.writeString(log.resolve("cfdi_fcl_forever.log.1970-01-01_08-00-55.490822996.gz"), "x");
        Files.writeString(log.resolve("fcl.log.1970-01-01_08-00-47.325898880.gz"), "x");
        Files.writeString(log.resolve("mtbf.log.2026-08-19_15-07-08.732487181.gz"), "x");
        Files.writeString(log.resolve("calib_history.log.gz"), "x");
        Files.writeString(log.resolve("CM_ROUTING.log_4.gz"), "x");
        Files.write(bundle.resolve("image/F100_front_wide.mfbag"), new byte[1024]);
        Files.writeString(bundle.resolve("help/common_config.json.gz"), "help");
        Files.writeString(bundle.resolve("meta"), "{}");
        Files.writeString(bundle.resolve("log_info.json"), "{}");
        Files.writeString(bundle.resolve("upload.done"), "");
        Files.writeString(bundle.resolve("dump_finish"), "");
        Files.writeString(bundle.resolve("good_flag"), "");
        Files.writeString(bundle.resolve("filter_meta"), "");
        return session;
    }

    /**
     * 用户给的真实路径形态回归：{@code /data/downloads/mmt_test/<md5>/<session>_public}。
     *
     * <p>断言「到底下哪些文件」（结构取自 NFS 真实会话）：
     * <ul>
     *   <li>收：{@code <bundle>/log/} 整目录（里面全是日志压缩包 {@code *.log.<ts>.gz}），
     *       加上与 log/ 同级的 {@code meta} / {@code log_info.json}；</li>
     *   <li>不收：原始数据包里的 {@code image/*.mfbag}（几十 MB）、{@code help/*.json.gz}；</li>
     *   <li>不收：平台簿记文件 {@code upload.done} / {@code dump_finish} / {@code good_flag} / {@code filter_meta}。</li>
     * </ul>
     */
    @Test
    void prepareLogDownload_RealSessionLayout_SelectsOnlyLogScope() throws Exception {
        Path session = buildRealSessionLayout(tempDir.resolve("nfs/downloads"), null);
        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-20")).thenReturn(
                sliceWithLogDir("slice-20", "case", session, REAL_MD5, REAL_SESSION));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-20");

        assertThat(download.files())
                .extracting(DataSliceService.LogDownload.LogFile::entryName)
                .containsExactlyInAnyOrder(
                        BUNDLE + "/log/cfdi_fcl_forever.log.1970-01-01_08-00-55.490822996.gz",
                        BUNDLE + "/log/fcl.log.1970-01-01_08-00-47.325898880.gz",
                        BUNDLE + "/log/mtbf.log.2026-08-19_15-07-08.732487181.gz",
                        BUNDLE + "/log/calib_history.log.gz",
                        BUNDLE + "/log/CM_ROUTING.log_4.gz",
                        BUNDLE + "/meta",
                        BUNDLE + "/log_info.json");
    }

    /**
     * 显式配置 {@code dir-names=log}、{@code dir-name-contains} 为空（与默认一致）：只收 log/ 整目录
     * 与同级 meta，bundle 里的辅助目录（image/help）与簿记文件不因在 log/ 之外而混进来。
     */
    @Test
    void prepareLogDownload_OnlyLogDirCollected_ExcludesAuxiliaryAndBookkeeping() throws Exception {
        Path session = buildRealSessionLayout(tempDir.resolve("nfs/downloads"), null);
        configureLogDownload(null, 50 * 1024 * 1024, null, "log", "");
        when(dataSliceMapper.selectById("slice-21")).thenReturn(
                sliceWithLogDir("slice-21", "case", session, REAL_MD5, REAL_SESSION));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-21");

        assertThat(download.files())
                .extracting(DataSliceService.LogDownload.LogFile::entryName)
                .containsExactlyInAnyOrder(
                        BUNDLE + "/log/cfdi_fcl_forever.log.1970-01-01_08-00-55.490822996.gz",
                        BUNDLE + "/log/fcl.log.1970-01-01_08-00-47.325898880.gz",
                        BUNDLE + "/log/mtbf.log.2026-08-19_15-07-08.732487181.gz",
                        BUNDLE + "/log/calib_history.log.gz",
                        BUNDLE + "/log/CM_ROUTING.log_4.gz",
                        BUNDLE + "/meta",
                        BUNDLE + "/log_info.json")
                .doesNotContain(
                        BUNDLE + "/image/F100_front_wide.mfbag",
                        BUNDLE + "/help/common_config.json.gz");
    }

    /** 平台簿记文件（upload.done / dump_finish / good_flag）不是日志，任何口径下都不该被下载。 */
    @Test
    void prepareLogDownload_BookkeepingFiles_AreNeverCollected() throws Exception {
        Path session = buildRealSessionLayout(tempDir.resolve("nfs/downloads"), null);
        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-26")).thenReturn(
                sliceWithLogDir("slice-26", "case", session, REAL_MD5, REAL_SESSION));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-26");

        assertThat(download.files())
                .extracting(f -> com.isim.dims.service.log.LogFileStore.fileName(f.path()))
                .doesNotContain("upload.done", "dump_finish", "good_flag", "F100_front_wide.mfbag");
    }

    /**
     * {@code log-scope=md5-dir}：同一 md5 下存在多个会话目录（实测 {@code 096b6130.../} 下有
     * {@code ..._9984/_9985/_9986_public} 三个），扫描根从会话目录上提一级到 md5 目录，
     * 把其中<b>所有</b>会话目录的日志与同级 meta 一起打包。
     */
    @Test
    void prepareLogDownload_Md5DirScope_CollectsAllSessionsUnderMd5() throws Exception {
        Path md5Dir = tempDir.resolve("md5scope/mmt_test").resolve(REAL_MD5);
        // session-1：含 log/ 与同级 meta
        Path s1 = md5Dir.resolve(REAL_SESSION);
        Files.createDirectories(s1.resolve("log"));
        Files.writeString(s1.resolve("log/s1.log.gz"), "s1");
        Files.writeString(s1.resolve("meta"), "{}");
        // session-2：另一个会话目录，含 log/ 与同级 log_info.json
        Path s2 = md5Dir.resolve("ea236d24_20260901081436109985_public");
        Files.createDirectories(s2.resolve("log"));
        Files.writeString(s2.resolve("log/s2.log.gz"), "s2");
        Files.writeString(s2.resolve("log_info.json"), "{}");
        // session-3：无日志的会话目录（只参与扫描，不产出日志文件）
        Path s3 = md5Dir.resolve("ea236d24_20260902121436109986_public");
        Files.createDirectories(s3.resolve("image"));
        Files.write(s3.resolve("image/x.mfbag"), new byte[64]);

        configureLogDownload(null, 50 * 1024 * 1024, null, "log", "", "md5-dir");
        when(dataSliceMapper.selectById("slice-27")).thenReturn(
                sliceWithLogDir("slice-27", "case", s1, REAL_MD5, REAL_SESSION));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-27");

        assertThat(download.files())
                .extracting(DataSliceService.LogDownload.LogFile::entryName)
                .containsExactlyInAnyOrder(
                        REAL_SESSION + "/log/s1.log.gz",
                        REAL_SESSION + "/meta",
                        "ea236d24_20260901081436109985_public/log/s2.log.gz",
                        "ea236d24_20260901081436109985_public/log_info.json");
        // 范围是 md5 目录本身（上提一级），而不是某一个会话目录
        assertThat(download.sourceDir()).isEqualTo(md5Dir.toString());
    }

    /** 库表记了日期段、磁盘上没有（反之亦然）→ 自动按候选形态命中，而不是报「路径不对」。 */
    @Test
    void prepareLogDownload_DbPathHasDateSegmentButDiskDoesNot_FallsBack() throws Exception {
        Path session = buildRealSessionLayout(tempDir.resolve("nfs/downloads"), null);
        Path dbPath = session.getParent().resolve("20260831").resolve(REAL_SESSION);

        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-22"))
                .thenReturn(sliceWithLogDir("slice-22", "case", dbPath, REAL_MD5, REAL_SESSION));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-22");

        assertThat(download.sourceDir()).isEqualTo(session.toString());
    }

    /** 库表少了日期段、磁盘上有 → 用 session_id 里的日期补出来再试。 */
    @Test
    void prepareLogDownload_DiskHasDateSegmentButDbDoesNot_InsertsFromSessionId() throws Exception {
        Path root = tempDir.resolve("nfs2/downloads");
        Path session = buildRealSessionLayout(root, "20260831");
        Path dbPath = root.resolve("mmt_test").resolve(REAL_MD5).resolve(REAL_SESSION); // 少了日期段

        configureLogDownload(null, 50 * 1024 * 1024);
        when(dataSliceMapper.selectById("slice-23"))
                .thenReturn(sliceWithLogDir("slice-23", "case", dbPath, REAL_MD5, REAL_SESSION));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-23");

        assertThat(download.sourceDir()).isEqualTo(session.toString());
    }

    /**
     * 本机没有 NFS 时用 {@code path-mappings} 复现线上路径：逻辑路径 {@code /data/downloads/...}
     * 映射到临时目录。白名单校验用逻辑路径，映射目标不必写进 allowed-roots。
     */
    @Test
    void prepareLogDownload_PathMapping_ResolvesLogicalPathToMappedRoot() throws Exception {
        Path mnt = tempDir.resolve("nfs3/downloads");
        Path session = buildRealSessionLayout(mnt, null);

        configureLogDownload("/data", 50 * 1024 * 1024,
                "/data/downloads>" + mnt, "log", "");
        when(dataSliceMapper.selectById("slice-24")).thenReturn(sliceWithLogDir("slice-24", "case",
                Path.of("/data/downloads/mmt_test/" + REAL_MD5 + "/" + REAL_SESSION),
                REAL_MD5, REAL_SESSION));

        DataSliceService.LogDownload download = dataSliceService.prepareLogDownload("slice-24");

        assertThat(download.sourceDir()).isEqualTo(session.toString());
        // 逻辑路径仍在白名单内 → 不因映射而被判越权
        assertThat(download.fileName()).endsWith("_logs.zip");
    }

    /** 映射配了但目标不存在 → 仍按「路径不对」报错，且消息里必须能看到试过哪些形态。 */
    @Test
    void prepareLogDownload_MissingEverywhere_ReportsTriedCandidates() {
        configureLogDownload("/data", 50 * 1024 * 1024,
                "/data/downloads>" + tempDir.resolve("nowhere"), "log", "");
        when(dataSliceMapper.selectById("slice-25")).thenReturn(sliceWithLogDir("slice-25", "case",
                Path.of("/data/downloads/mmt_test/" + REAL_MD5 + "/" + REAL_SESSION),
                REAL_MD5, REAL_SESSION));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dataSliceService.prepareLogDownload("slice-25"));

        assertThat(ex.getMessage()).startsWith("LOG_PATH_NOT_FOUND");
        assertThat(ex.getMessage()).contains("tried=").contains("os=")
                .contains("20260831").contains("parent entries");
    }
}
