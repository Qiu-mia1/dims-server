package com.isim.dims.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.isim.dims.dto.ExportListResponse;
import com.isim.dims.dto.ExportRequest;
import com.isim.dims.dto.ExportResponse;
import com.isim.dims.dto.ExportTaskSummary;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.ExportTask;
import com.isim.dims.exception.StorageArchivedException;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ExportTaskMapper;
import com.isim.dims.service.impl.ExportServiceImpl;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportServiceImplTest {

    @Mock
    private DataSliceMapper dataSliceMapper;

    @Mock
    private ExportTaskMapper exportTaskMapper;

    @Mock
    private PythonExecutor pythonExecutor;

    @Mock
    private ExportPipelineService exportPipelineService;

    @Mock
    private StorageAccess storageAccess;

    @InjectMocks
    private ExportServiceImpl exportService;

    @Captor
    private ArgumentCaptor<ExportTask> exportTaskCaptor;

    static {
        // 纯单元测试无 MyBatis 上下文：手动注册 TableInfo，
        // 使 LambdaQueryWrapper 的列名解析（getSqlSegment / getParamNameValuePairs）可用
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ExportTask.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(exportService, "bagDir", "/data/bags");
        ReflectionTestUtils.setField(exportService, "exportDir", "/data/exports");
    }

    // ============ createExport() ============

    @Test
    void createExport_SliceReady_CreatesTask() {
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setFilePath("/data/bags/slice-1/original.bag");
        slice.setStatus("READY");

        ExportRequest request = new ExportRequest();
        request.setStartTs("2026-07-24T09:00:00Z");
        request.setEndTs("2026-07-24T09:30:00Z");
        request.setTopics(List.of("/sensor/camera"));
        request.setFormat("bag");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        when(exportTaskMapper.insert(exportTaskCaptor.capture())).thenReturn(1);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of());

        ExportResponse response = exportService.createExport("slice-1", request);

        assertThat(response.getSliceId()).isEqualTo("slice-1");
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getFormat()).isEqualTo("bag");
        assertThat(response.getExportId()).isNotNull();

        ExportTask createdTask = exportTaskCaptor.getValue();
        assertThat(createdTask.getSliceId()).isEqualTo("slice-1");
        assertThat(createdTask.getStatus()).isEqualTo("PENDING");
        assertThat(createdTask.getFormat()).isEqualTo("bag");
        assertThat(createdTask.getTopics()).contains("camera");
        assertThat(createdTask.getExpiresAt()).isAfter(LocalDateTime.now());

        verify(exportPipelineService).processExportPipeline(
                eq(createdTask), eq(slice.getFilePath()), eq(request));
    }

    @Test
    void createExport_DefaultFormat_UsesBag() {
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setFilePath("/data/bags/slice-1/original.bag");
        slice.setStatus("READY");

        ExportRequest request = new ExportRequest();
        request.setStartTs("2026-07-24T09:00:00Z");
        request.setEndTs("2026-07-24T09:30:00Z");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of());
        when(exportTaskMapper.insert(any(ExportTask.class))).thenReturn(1);

        ExportResponse response = exportService.createExport("slice-1", request);

        assertThat(response.getFormat()).isEqualTo("bag");
    }

    @Test
    void createExport_CacheReuse_ReturnsExistingExport() {
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setFilePath("/data/bags/slice-1/original.bag");
        slice.setStatus("READY");

        ExportRequest request = new ExportRequest();
        request.setStartTs("2026-07-24T09:00:00Z");
        request.setEndTs("2026-07-24T09:30:00Z");
        request.setFormat("bag");

        ExportTask existingTask = new ExportTask();
        existingTask.setId("existing-export");
        existingTask.setSliceId("slice-1");
        existingTask.setStatus("READY");
        existingTask.setFormat("bag");
        existingTask.setStartTs(LocalDateTime.of(2026, Month.JULY, 24, 9, 0, 0));
        existingTask.setEndTs(LocalDateTime.of(2026, Month.JULY, 24, 9, 30, 0));
        existingTask.setFilePath("/data/exports/slice-1/existing/output.bag");
        existingTask.setFileSize(500L);
        existingTask.setExpiresAt(LocalDateTime.now().plusHours(23));
        existingTask.setCreatedAt(LocalDateTime.now().minusHours(1));

        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of(existingTask));

        ExportResponse response = exportService.createExport("slice-1", request);

        assertThat(response.getExportId()).isEqualTo("existing-export");
        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getFileSize()).isEqualTo(500L);

        verify(exportTaskMapper, never()).insert(any(ExportTask.class));
        verify(exportPipelineService, never()).processExportPipeline(any(ExportTask.class), anyString(), any());
    }

    @Test
    void createExport_SliceNotFound_ThrowsException() {
        when(dataSliceMapper.selectById("invalid")).thenReturn(null);

        ExportRequest request = new ExportRequest();
        request.setStartTs("2026-07-24T09:00:00Z");
        request.setEndTs("2026-07-24T09:30:00Z");

        assertThrows(RuntimeException.class,
                () -> exportService.createExport("invalid", request));

        verify(exportTaskMapper, never()).insert(any(ExportTask.class));
    }

    @Test
    void createExport_SliceNotReady_ThrowsException() {
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("UPLOADING");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);

        ExportRequest request = new ExportRequest();
        request.setStartTs("2026-07-24T09:00:00Z");
        request.setEndTs("2026-07-24T09:30:00Z");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> exportService.createExport("slice-1", request));
        assertThat(exception.getMessage()).contains("INVALID_SLICE_STATUS");
    }

    @Test
    void createExport_ArchivedSlice_ThrowsStorageArchived() {
        // Review W3：minio 模式归档切片（storage_type=LOCAL）发起导出 → 入队前立即
        // StorageArchivedException（Controller 映射 410），不落导出任务
        DataSlice slice = new DataSlice();
        slice.setId("slice-1");
        slice.setStatus("READY");
        slice.setStorageType("LOCAL");

        when(dataSliceMapper.selectById("slice-1")).thenReturn(slice);
        doThrow(new StorageArchivedException("slice", "slice-1"))
                .when(storageAccess).assertNotArchived(slice);

        ExportRequest request = new ExportRequest();
        request.setStartTs("2026-07-24T09:00:00Z");
        request.setEndTs("2026-07-24T09:30:00Z");

        assertThrows(StorageArchivedException.class,
                () -> exportService.createExport("slice-1", request));
        verify(exportTaskMapper, never()).insert(any(ExportTask.class));
        verify(exportPipelineService, never()).processExportPipeline(any(), any(), any());
    }

    // ============ getStatus() ============

    @Test
    void getStatus_Found_ReturnsExportResponse() {
        ExportTask task = new ExportTask();
        task.setId("export-1");
        task.setSliceId("slice-1");
        task.setStatus("READY");
        task.setFormat("bag");
        task.setFileSize(2048L);
        task.setFilePath("/data/exports/slice-1/export-1/output.bag");
        task.setCreatedAt(LocalDateTime.now().minusHours(1));
        task.setExpiresAt(LocalDateTime.now().plusHours(23));

        when(exportTaskMapper.selectById("export-1")).thenReturn(task);

        ExportResponse response = exportService.getStatus("export-1");

        assertThat(response.getExportId()).isEqualTo("export-1");
        assertThat(response.getSliceId()).isEqualTo("slice-1");
        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getFileSize()).isEqualTo(2048L);
        assertThat(response.getDownloadUrl()).isEqualTo("/v1/exports/export-1/download");
    }

    @Test
    void getStatus_NotFound_ThrowsException() {
        when(exportTaskMapper.selectById("invalid")).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> exportService.getStatus("invalid"));
    }

    @Test
    void getStatus_Processing_DownloadUrlIsNull() {
        ExportTask task = buildTask("export-1", "slice-1", "PROCESSING", "bag", null);
        when(exportTaskMapper.selectById("export-1")).thenReturn(task);

        ExportResponse response = exportService.getStatus("export-1");

        assertThat(response.getStatus()).isEqualTo("PROCESSING");
        assertThat(response.getDownloadUrl()).isNull();
    }

    // ============ listExports() ============

    private static final LocalDateTime T0 = LocalDateTime.of(2026, Month.JULY, 24, 9, 0, 0);

    private ExportTask buildTask(String id, String sliceId, String status, String format, Long fileSize) {
        ExportTask t = new ExportTask();
        t.setId(id);
        t.setSliceId(sliceId);
        t.setStatus(status);
        t.setFormat(format);
        t.setFileSize(fileSize);
        t.setStartTs(T0);
        t.setEndTs(T0.plusHours(1));
        t.setCreatedAt(T0);
        t.setUpdatedAt(T0.plusMinutes(10));
        t.setExpiresAt(T0.plusHours(24));
        return t;
    }

    private DataSlice buildSlice(String id, String name) {
        DataSlice s = new DataSlice();
        s.setId(id);
        s.setName(name);
        return s;
    }

    @Test
    void listExports_NonReadyTask_DownloadUrlIsNull() {
        when(exportTaskMapper.selectCount(any())).thenReturn(1L);
        ExportTask t1 = buildTask("export-1", "slice-1", "PROCESSING", "bag", null);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of(t1));
        when(dataSliceMapper.selectBatchIds(any())).thenReturn(List.of(buildSlice("slice-1", "route-1")));

        ExportListResponse resp = exportService.listExports(null, null, 1, 20);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getStatus()).isEqualTo("PROCESSING");
        assertThat(resp.getItems().get(0).getDownloadUrl()).isNull();
    }

    @Test
    void listExports_NoFilter_ReturnsPagedResultWithSliceName() {
        when(exportTaskMapper.selectCount(any())).thenReturn(1L);
        ExportTask t1 = buildTask("export-1", "slice-1", "READY", "bag", 2048L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of(t1));
        when(dataSliceMapper.selectBatchIds(any())).thenReturn(List.of(buildSlice("slice-1", "route-1")));

        ExportListResponse resp = exportService.listExports(null, null, 1, 20);

        assertThat(resp.getPage()).isEqualTo(1);
        assertThat(resp.getPageSize()).isEqualTo(20);
        assertThat(resp.getTotal()).isEqualTo(1L);
        assertThat(resp.getItems()).hasSize(1);

        ExportTaskSummary item = resp.getItems().get(0);
        assertThat(item.getExportId()).isEqualTo("export-1");
        assertThat(item.getSliceId()).isEqualTo("slice-1");
        assertThat(item.getSliceName()).isEqualTo("route-1");
        assertThat(item.getStatus()).isEqualTo("READY");
        assertThat(item.getFormat()).isEqualTo("bag");
        assertThat(item.getFileSize()).isEqualTo(2048L);
        assertThat(item.getDownloadUrl()).isEqualTo("/v1/exports/export-1/download");
        assertThat(item.getCreatedAt()).isEqualTo("2026-07-24T09:00:00Z");
        assertThat(item.getUpdatedAt()).isEqualTo("2026-07-24T09:10:00Z");
    }

    @Test
    void listExports_CountWrapperHasNoOrderBy() {
        when(exportTaskMapper.selectCount(any())).thenReturn(1L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of());

        exportService.listExports(null, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<ExportTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(exportTaskMapper).selectCount(captor.capture());
        // PG 拒绝聚合 count 带 ORDER BY（MySQL 容忍）：count wrapper 必须不含排序
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("ORDER BY");
    }

    @Test
    void listExports_Page2_UsesLimitOffset() {
        when(exportTaskMapper.selectCount(any())).thenReturn(45L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of());

        exportService.listExports(null, null, 2, 20);

        ArgumentCaptor<LambdaQueryWrapper<ExportTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(exportTaskMapper).selectList(captor.capture());
        // getSqlSegment() 触发列名解析（MP 3.5.9 惰性解析），SQL 末尾应为 lastSql
        assertThat(captor.getValue().getSqlSegment()).endsWith("LIMIT 20 OFFSET 20");
    }

    @Test
    void listExports_WithSliceId_ValidatesSliceAndFilters() {
        when(dataSliceMapper.selectById("slice-1")).thenReturn(buildSlice("slice-1", "route-1"));
        when(exportTaskMapper.selectCount(any())).thenReturn(1L);
        ExportTask t1 = buildTask("export-1", "slice-1", "READY", "bag", 10L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of(t1));
        when(dataSliceMapper.selectBatchIds(any())).thenReturn(List.of(buildSlice("slice-1", "route-1")));

        ExportListResponse resp = exportService.listExports("slice-1", null, 1, 20);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSliceId()).isEqualTo("slice-1");

        ArgumentCaptor<LambdaQueryWrapper<ExportTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(exportTaskMapper).selectCount(captor.capture());
        // getSqlSegment() 触发惰性列名解析与参数生成，之后 paramNameValuePairs 才有值
        LambdaQueryWrapper<ExportTask> captured = captor.getValue();
        captured.getSqlSegment();
        assertThat(captured.getSqlSegment()).contains("slice_id =");
        assertThat(captured.getParamNameValuePairs()).containsValue("slice-1");
    }

    @Test
    void listExports_WithStatus_FiltersByStatus() {
        when(exportTaskMapper.selectCount(any())).thenReturn(1L);
        ExportTask t1 = buildTask("export-1", "slice-1", "READY", "bag", 10L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of(t1));
        when(dataSliceMapper.selectBatchIds(any())).thenReturn(List.of(buildSlice("slice-1", "route-1")));

        ExportListResponse resp = exportService.listExports(null, "READY", 1, 20);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getStatus()).isEqualTo("READY");

        ArgumentCaptor<LambdaQueryWrapper<ExportTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(exportTaskMapper).selectCount(captor.capture());
        // getSqlSegment() 触发惰性列名解析与参数生成，之后 paramNameValuePairs 才有值
        LambdaQueryWrapper<ExportTask> captured = captor.getValue();
        captured.getSqlSegment();
        assertThat(captured.getSqlSegment()).contains("status =");
        assertThat(captured.getParamNameValuePairs()).containsValue("READY");
    }

    @Test
    void listExports_SliceNotFound_Throws() {
        when(dataSliceMapper.selectById("invalid")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> exportService.listExports("invalid", null, 1, 20));

        assertThat(ex.getMessage()).startsWith("SLICE_NOT_FOUND:");
        verify(exportTaskMapper, never()).selectCount(any());
        verify(exportTaskMapper, never()).selectList(any());
    }

    @Test
    void listExports_OrdersByCreatedAtDesc() {
        when(exportTaskMapper.selectCount(any())).thenReturn(0L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of());

        exportService.listExports(null, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<ExportTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(exportTaskMapper).selectList(captor.capture());
        // getSqlSegment() 触发惰性列名解析（MP 3.5.9），之后 SQL 段才可断言
        assertThat(captor.getValue().getSqlSegment()).contains("ORDER BY created_at DESC");
    }

    @Test
    void listExports_NoMatch_ReturnsEmptyAndSkipsBatchLookup() {
        when(exportTaskMapper.selectCount(any())).thenReturn(0L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of());

        ExportListResponse resp = exportService.listExports(null, "ERROR", 1, 20);

        assertThat(resp.getItems()).isEmpty();
        assertThat(resp.getTotal()).isZero();
        verify(dataSliceMapper, never()).selectBatchIds(any());
    }

    @Test
    void listExports_SliceMissingInBatchMap_SliceNameIsNull() {
        when(exportTaskMapper.selectCount(any())).thenReturn(1L);
        ExportTask t1 = buildTask("export-1", "orphan-slice", "READY", "bag", 10L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of(t1));
        when(dataSliceMapper.selectBatchIds(any())).thenReturn(List.of());

        ExportListResponse resp = exportService.listExports(null, null, 1, 20);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSliceName()).isNull();
    }

    @Test
    void listExports_MultipleTasksSameSlice_BatchLoadsUniqueIds() {
        when(exportTaskMapper.selectCount(any())).thenReturn(2L);
        ExportTask t1 = buildTask("export-1", "slice-1", "READY", "bag", 10L);
        ExportTask t2 = buildTask("export-2", "slice-1", "PROCESSING", "mp4", 20L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of(t1, t2));
        when(dataSliceMapper.selectBatchIds(any())).thenReturn(List.of(buildSlice("slice-1", "route-1")));

        ExportListResponse resp = exportService.listExports(null, null, 1, 20);

        assertThat(resp.getItems()).hasSize(2);
        assertThat(resp.getItems()).extracting(ExportTaskSummary::getSliceName).containsOnly("route-1");

        ArgumentCaptor<java.util.Collection> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(dataSliceMapper).selectBatchIds(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue()).containsExactly("slice-1");
    }

    @Test
    void listExports_BlankSliceId_TreatedAsNoFilter() {
        when(exportTaskMapper.selectCount(any())).thenReturn(0L);
        when(exportTaskMapper.selectList(any())).thenReturn(List.of());

        ExportListResponse resp = exportService.listExports("   ", null, 1, 20);

        assertThat(resp.getItems()).isEmpty();
        verify(dataSliceMapper, never()).selectById(any());
        verify(dataSliceMapper, never()).selectBatchIds(any());
    }
}
