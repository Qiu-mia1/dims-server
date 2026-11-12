package com.isim.dims.controller;

import com.isim.dims.dto.FilterOption;
import com.isim.dims.dto.SliceDetailResponse;
import com.isim.dims.dto.SliceListResponse;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.schedule.DownloadHirain835Schedule;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.BosDirectoryRestoreService;
import com.isim.dims.service.DataSliceService;
import com.isim.dims.service.FisheyePreviewService;
import com.isim.dims.service.LocalDirectoryImportService;
import com.isim.dims.service.SliceLoadService;
import com.isim.dims.service.VideoSemanticSearchService;
import com.isim.dims.service.impl.FilterOptionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DataSliceController.class)
@Import(SliceSecurityTestConfig.class)
class DataSliceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSliceService dataSliceService;

    @MockBean
    private LocalDirectoryImportService localDirectoryImportService;

    /** 触发类型下拉选项提供者（纯内存读配置，切片测试里 mock 掉即可） */
    @MockBean
    private FilterOptionProvider filterOptionProvider;

    /**
     * Controller 的其余依赖 —— {@code @WebMvcTest} 切片不加载 MyBatis / BOS 自动配置，
     * 不显式 mock 就会因缺 bean 导致 {@code dataSliceController} 装配失败，
     * 整个 ApplicationContext 起不来（本类所有用例一起 ERROR）。
     */
    @MockBean
    private BosDirectoryRestoreService bosDirectoryRestoreService;

    @MockBean
    private DataSliceMapper dataSliceMapper;

    /** 按需加载 Service（meta-only 切片点「加载」触发） */
    @MockBean
    private SliceLoadService sliceLoadService;

    /** 前视鱼眼预览（未加载切片可播放的一路视频） */
    @MockBean
    private FisheyePreviewService fisheyePreviewService;

    @MockBean
    private VideoSemanticSearchService videoSemanticSearchService;

    /** Hirain 835 定时下载调度器（重新拉取源数据走完整 BOS → 转换流水线） */
    @MockBean
    private DownloadHirain835Schedule downloadHirain835Schedule;

    // ============ POST /v1/data-slices ============

    @Test
    void upload_WithValidFile_Returns202() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test_route.bag", MediaType.APPLICATION_OCTET_STREAM_VALUE, "fake bag content".getBytes());

        when(dataSliceService.upload(any(), any(), eq("MMT")))
                .thenReturn(Map.of("slice_id", "s1", "task_id", "t1", "status", "UPLOADING"));

        mockMvc.perform(multipart("/v1/data-slices").file(file).param("source", "MMT"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.slice_id").value("s1"))
                .andExpect(jsonPath("$.task_id").value("t1"))
                .andExpect(jsonPath("$.status").value("UPLOADING"));

        verify(dataSliceService).upload(any(), any(), eq("MMT"));
    }

    @Test
    void upload_WithNameParam_Returns202() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.bag", MediaType.APPLICATION_OCTET_STREAM_VALUE, "data".getBytes());

        when(dataSliceService.upload(any(), eq("my-slice"), eq("ZYT")))
                .thenReturn(Map.of("slice_id", "s1", "task_id", "t1", "status", "UPLOADING"));

        mockMvc.perform(multipart("/v1/data-slices").file(file).param("name", "my-slice").param("source", "ZYT"))
                .andExpect(status().isAccepted());
    }

    @Test
    void upload_WithoutSource_Returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.bag", MediaType.APPLICATION_OCTET_STREAM_VALUE, "data".getBytes());

        mockMvc.perform(multipart("/v1/data-slices").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void upload_InvalidSource_Returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.bag", MediaType.APPLICATION_OCTET_STREAM_VALUE, "data".getBytes());

        mockMvc.perform(multipart("/v1/data-slices").file(file).param("source", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void upload_WithoutFile_Returns400() throws Exception {
        mockMvc.perform(multipart("/v1/data-slices").param("source", "MMT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_ServiceThrowsException_Returns500() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.bag", MediaType.APPLICATION_OCTET_STREAM_VALUE, "content".getBytes());

        when(dataSliceService.upload(any(), any(), eq("MMT")))
                .thenThrow(new RuntimeException("Disk full"));

        mockMvc.perform(multipart("/v1/data-slices").file(file).param("source", "MMT"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_FAILED"))
                .andExpect(jsonPath("$.error.message").exists());
    }

    // ============ GET /v1/data-slices ============

    @Test
    void search_DefaultParams_Returns200() throws Exception {
        SliceListResponse response = new SliceListResponse();
        response.setItems(List.of());
        response.setTotal(0L);
        response.setPage(1);
        response.setPageSize(50);

        when(dataSliceService.search(any())).thenReturn(response);

        mockMvc.perform(get("/v1/data-slices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void search_WithAllQueryParams_Returns200() throws Exception {
        SliceListResponse response = new SliceListResponse();
        SliceListResponse.SliceItem item = new SliceListResponse.SliceItem();
        item.setId("slice-1");
        item.setName("test-slice");
        item.setStatus("READY");
        response.setItems(List.of(item));
        response.setTotal(1L);
        response.setPage(1);
        response.setPageSize(20);

        when(dataSliceService.search(any())).thenReturn(response);

        mockMvc.perform(get("/v1/data-slices")
                        .param("start_ts", "2026-07-24T09:00:00Z")
                        .param("end_ts", "2026-07-24T10:00:00Z")
                        .param("status", "READY")
                        .param("keyword", "test")
                        .param("sensor_types", "camera,lidar")
                        .param("tags", "tag-1,tag-2")
                        .param("lat", "31.2")
                        .param("lon", "121.4")
                        .param("radius", "1000")
                        .param("page", "1")
                        .param("page_size", "20")
                        .param("sort", "created_at:asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].id").value("slice-1"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void search_WithNewFilterParams_Returns200() throws Exception {
        SliceListResponse response = new SliceListResponse();
        SliceListResponse.SliceItem item = new SliceListResponse.SliceItem();
        item.setId("slice-2");
        item.setName("cfdi-test");
        item.setSourceType("MMT");
        response.setItems(List.of(item));
        response.setTotal(1L);
        response.setPage(1);
        response.setPageSize(20);

        when(dataSliceService.search(any())).thenReturn(response);

        mockMvc.perform(get("/v1/data-slices")
                        .param("source_type", "MMT")
                        .param("event_name", "mid_highbeam")
                        .param("car_type", "CX835ICA")
                        .param("project", "LUS")
                        .param("tag", "远光灯")
                        .param("start_date", "2026-07-01T00:00:00Z")
                        .param("end_date", "2026-07-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].id").value("slice-2"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void search_ServiceThrowsException_Returns500() throws Exception {
        when(dataSliceService.search(any()))
                .thenThrow(new RuntimeException("Search failed"));

        mockMvc.perform(get("/v1/data-slices"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("SEARCH_FAILED"));
    }

    // ============ GET /v1/data-slices/filter-options ============

    @Test
    void filterOptions_ReturnsGroupedFiltersInSnakeCase() throws Exception {
        when(filterOptionProvider.list()).thenReturn(List.of(
                FilterOption.builder()
                        .filter("imu_collision")
                        .eventNames(List.of("imu_collision"))
                        .titles(List.of("IMU碰撞"))
                        .build(),
                FilterOption.builder()
                        .filter("highbeam_on_filter")
                        .eventNames(List.of("high_highbeam_on", "mid_highbeam_on"))
                        .titles(List.of("打点", "打点"))
                        .build()));

        mockMvc.perform(get("/v1/data-slices/filter-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters").isArray())
                .andExpect(jsonPath("$.filters.length()").value(2))
                .andExpect(jsonPath("$.filters[0].filter").value("imu_collision"))
                .andExpect(jsonPath("$.filters[0].event_names[0]").value("imu_collision"))
                .andExpect(jsonPath("$.filters[0].titles[0]").value("IMU碰撞"))
                // 一个筛选器映射多个事件：event_names 与 titles 同长、同下标一一对应
                .andExpect(jsonPath("$.filters[1].filter").value("highbeam_on_filter"))
                .andExpect(jsonPath("$.filters[1].event_names.length()").value(2))
                .andExpect(jsonPath("$.filters[1].event_names[1]").value("mid_highbeam_on"))
                .andExpect(jsonPath("$.filters[1].titles[1]").value("打点"));
    }

    /**
     * 关键回归：{@code /filter-options} 是字面量路径，必须命中新端点，
     * 不能被 {@code /{sliceId}} 当成切片 ID 吞掉。
     *
     * <p>若被吞掉，调用链会变成 {@code getDetail("filter-options")} —— 所以断 200
     * 之外还要 verify 详情方法从未被调用。</p>
     */
    @Test
    void filterOptions_IsNotSwallowedBySliceIdRoute() throws Exception {
        when(filterOptionProvider.list()).thenReturn(List.of());

        mockMvc.perform(get("/v1/data-slices/filter-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters").isArray());

        verify(dataSliceService, never()).getDetail(anyString());
    }

    @Test
    void filterOptions_EmptyConfig_ReturnsEmptyArray() throws Exception {
        when(filterOptionProvider.list()).thenReturn(List.of());

        mockMvc.perform(get("/v1/data-slices/filter-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters").isEmpty())
                // 空数组而不是 null：前端直接数组展开，无需判空
                .andExpect(jsonPath("$.filters").isArray());
    }

    // ============ GET /v1/data-slices/{sliceId} ============

    @Test
    void getDetail_Found_Returns200() throws Exception {
        SliceDetailResponse detail = new SliceDetailResponse();
        detail.setId("slice-1");
        detail.setName("test-slice");
        detail.setStatus("READY");
        detail.setTaskId("task-1");

        when(dataSliceService.getDetail("slice-1")).thenReturn(detail);

        mockMvc.perform(get("/v1/data-slices/{sliceId}", "slice-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("slice-1"))
                .andExpect(jsonPath("$.name").value("test-slice"))
                .andExpect(jsonPath("$.task_id").value("task-1"));
    }

    @Test
    void getDetail_NotFound_Returns404() throws Exception {
        when(dataSliceService.getDetail("invalid-id"))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: invalid-id"));

        mockMvc.perform(get("/v1/data-slices/{sliceId}", "invalid-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ DELETE /v1/data-slices/{sliceId} ============

    @Test
    void delete_Success_Returns204() throws Exception {
        doNothing().when(dataSliceService).delete("slice-1");

        mockMvc.perform(delete("/v1/data-slices/{sliceId}", "slice-1"))
                .andExpect(status().isNoContent());

        verify(dataSliceService).delete("slice-1");
    }

    @Test
    void delete_NotFound_Returns500() throws Exception {
        doThrow(new RuntimeException("SLICE_NOT_FOUND: invalid-id"))
                .when(dataSliceService).delete("invalid-id");

        mockMvc.perform(delete("/v1/data-slices/{sliceId}", "invalid-id"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ POST /v1/data-slices/{sliceId}/load（按需加载） ============

    @Test
    void startLoad_Accepted_Returns202WithEstimate() throws Exception {
        when(sliceLoadService.startLoad("s1")).thenReturn(Map.of(
                "slice_id", "s1",
                "task_id", "t1",
                "status", "LOADING",
                "estimated_seconds", 300L,
                "total_bytes", 1024L,
                "downloaded_bytes", 0L,
                "remaining_bytes", 1024L));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/load", "s1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.slice_id").value("s1"))
                .andExpect(jsonPath("$.task_id").value("t1"))
                .andExpect(jsonPath("$.status").value("LOADING"))
                .andExpect(jsonPath("$.estimated_seconds").value(300));

        verify(sliceLoadService).startLoad("s1");
    }

    @Test
    void startLoad_SliceNotFound_Returns404() throws Exception {
        when(sliceLoadService.startLoad("missing"))
                .thenThrow(new IllegalStateException("SLICE_NOT_FOUND: sliceId=missing"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/load", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    @Test
    void startLoad_StateInvalid_Returns400() throws Exception {
        when(sliceLoadService.startLoad("s2"))
                .thenThrow(new IllegalStateException("LOAD_STATE_INVALID: 切片状态 READY 已加载完成"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/load", "s2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LOAD_STATE_INVALID"));
    }

    @Test
    void startLoad_AlreadyLoading_Returns400() throws Exception {
        when(sliceLoadService.startLoad("s3"))
                .thenThrow(new IllegalStateException("LOAD_IN_PROGRESS: 切片正在加载中"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/load", "s3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LOAD_IN_PROGRESS"));
    }

    // ============ GET /v1/data-slices/{sliceId}/load/status ============

    @Test
    void loadStatus_Downloading_Returns200WithProgress() throws Exception {
        when(sliceLoadService.getLoadStatus("s1")).thenReturn(Map.of(
                "slice_id", "s1",
                "status", "LOADING",
                "stage", "DOWNLOADING",
                "progress", 10,
                "remaining_seconds", 240L,
                "estimated_seconds", 300L));

        mockMvc.perform(get("/v1/data-slices/{sliceId}/load/status", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("DOWNLOADING"))
                .andExpect(jsonPath("$.progress").value(10))
                .andExpect(jsonPath("$.remaining_seconds").value(240));
    }

    @Test
    void loadStatus_SliceNotFound_Returns404() throws Exception {
        when(sliceLoadService.getLoadStatus("missing"))
                .thenThrow(new IllegalStateException("SLICE_NOT_FOUND: sliceId=missing"));

        mockMvc.perform(get("/v1/data-slices/{sliceId}/load/status", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    // ============ POST /v1/data-slices/reimport ============

    @Test
    void reimport_Hirain_Returns202WithNewSliceId() throws Exception {
        DataSlice hirainSlice = new DataSlice();
        hirainSlice.setId("hirain-s1");
        hirainSlice.setName("hirain-original");
        hirainSlice.setSourceType("Hirain");
        hirainSlice.setImportBosKey("raw/hirain/20261023/session_001");

        when(dataSliceMapper.selectById("hirain-s1")).thenReturn(hirainSlice);
        when(localDirectoryImportService.countConcurrentTasks()).thenReturn(0L);
        when(downloadHirain835Schedule.reprocessSubdir("raw/hirain/20261023/session_001", "hirain-original"))
                .thenReturn("hirain-s2");

        mockMvc.perform(post("/v1/data-slices/reimport")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slice_ids\":[\"hirain-s1\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].sliceId").value("hirain-s1"))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[0].newSliceId").value("hirain-s2"));

        verify(dataSliceService).delete("hirain-s1");
        verify(downloadHirain835Schedule).reprocessSubdir("raw/hirain/20261023/session_001", "hirain-original");
    }

    @Test
    void reimport_HirainMissingBosKey_ReturnsFailed() throws Exception {
        DataSlice hirainSlice = new DataSlice();
        hirainSlice.setId("hirain-s1");
        hirainSlice.setName("hirain-original");
        hirainSlice.setSourceType("Hirain");
        hirainSlice.setImportBosKey(null);

        when(dataSliceMapper.selectById("hirain-s1")).thenReturn(hirainSlice);
        when(localDirectoryImportService.countConcurrentTasks()).thenReturn(0L);

        mockMvc.perform(post("/v1/data-slices/reimport")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slice_ids\":[\"hirain-s1\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].sliceId").value("hirain-s1"))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].message").value("REIMPORT_SOURCE_MISSING: importBosKey is empty, sliceId=hirain-s1"));

        verify(dataSliceService, never()).delete(anyString());
        verify(downloadHirain835Schedule, never()).reprocessSubdir(anyString(), anyString());
    }

    // ============ GET /v1/data-slices/{sliceId}/preview（前视鱼眼预览） ============

    @Test
    void preview_HasPreview_ReturnsPlayableUrl() throws Exception {
        when(fisheyePreviewService.hasPreview("s1")).thenReturn(true);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/preview", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slice_id").value("s1"))
                .andExpect(jsonPath("$.has_preview").value(true))
                .andExpect(jsonPath("$.url").value("/data/videos/s1/preview_front_fisheye.mp4"));
    }

    @Test
    void preview_NotGenerated_ReturnsFalse() throws Exception {
        when(fisheyePreviewService.hasPreview("s1")).thenReturn(false);

        mockMvc.perform(get("/v1/data-slices/{sliceId}/preview", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_preview").value(false));
    }
}
