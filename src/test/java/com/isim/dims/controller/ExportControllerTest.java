package com.isim.dims.controller;

import com.isim.dims.dto.ExportListResponse;
import com.isim.dims.dto.ExportResponse;
import com.isim.dims.dto.ExportTaskSummary;
import com.isim.dims.exception.SliceNotFoundException;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.service.ExportService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExportController.class)
@Import(SliceSecurityTestConfig.class)
class ExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExportService exportService;

    // ============ POST /v1/data-slices/{sliceId}/exports ============

    @Test
    void createExport_ValidRequest_Returns202() throws Exception {
        ExportResponse response = new ExportResponse();
        response.setExportId("export-1");
        response.setSliceId("slice-1");
        response.setStatus("PENDING");
        response.setFormat("bag");

        when(exportService.createExport(eq("slice-1"), any())).thenReturn(response);

        mockMvc.perform(post("/v1/data-slices/{sliceId}/exports", "slice-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start_ts\":\"2026-07-24T09:00:00Z\",\"end_ts\":\"2026-07-24T09:30:00Z\",\"format\":\"bag\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.export_id").value("export-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.format").value("bag"));
    }

    @Test
    void createExport_SliceNotFound_Returns500() throws Exception {
        when(exportService.createExport(eq("invalid"), any()))
                .thenThrow(new RuntimeException("SLICE_NOT_FOUND: invalid"));

        mockMvc.perform(post("/v1/data-slices/{sliceId}/exports", "invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start_ts\":\"2026-07-24T09:00:00Z\",\"end_ts\":\"2026-07-24T09:30:00Z\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("EXPORT_FAILED"));
    }

    @Test
    void createExport_MinimalBody_Returns202() throws Exception {
        ExportResponse resp = new ExportResponse();
        resp.setExportId("export-min");
        when(exportService.createExport(eq("slice-1"), any())).thenReturn(resp);

        mockMvc.perform(post("/v1/data-slices/{sliceId}/exports", "slice-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start_ts\":\"2026-07-24T09:00:00Z\",\"end_ts\":\"2026-07-24T09:30:00Z\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void createExport_InvalidJson_Returns400() throws Exception {
        mockMvc.perform(post("/v1/data-slices/{sliceId}/exports", "slice-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    // ============ GET /v1/exports/{exportId} ============

    @Test
    void getExportStatus_Found_Returns200() throws Exception {
        ExportResponse response = new ExportResponse();
        response.setExportId("export-1");
        response.setSliceId("slice-1");
        response.setStatus("READY");
        response.setFormat("bag");
        response.setFileSize(1024L);
        response.setDownloadUrl("/data/exports/slice-1/export-1/output.bag");

        when(exportService.getStatus("export-1")).thenReturn(response);

        mockMvc.perform(get("/v1/exports/{exportId}", "export-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.export_id").value("export-1"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.file_size").value(1024));
    }

    @Test
    void getExportStatus_NotFound_Returns500() throws Exception {
        when(exportService.getStatus("invalid"))
                .thenThrow(new RuntimeException("EXPORT_NOT_FOUND: invalid"));

        mockMvc.perform(get("/v1/exports/{exportId}", "invalid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("EXPORT_NOT_FOUND"));
    }

    // ============ GET /v1/exports (全局导出列表) ============

    private ExportTaskSummary buildSummary(String exportId, String sliceId, String sliceName, String status) {
        ExportTaskSummary s = new ExportTaskSummary();
        s.setExportId(exportId);
        s.setSliceId(sliceId);
        s.setSliceName(sliceName);
        s.setStatus(status);
        s.setFormat("bag");
        s.setCreatedAt("2026-07-24T09:00:00Z");
        s.setDownloadUrl("/v1/exports/" + exportId + "/download");
        return s;
    }

    private ExportListResponse buildListResponse(int page, int pageSize, long total, List<ExportTaskSummary> items) {
        ExportListResponse resp = new ExportListResponse();
        resp.setItems(items);
        resp.setTotal(total);
        resp.setPage(page);
        resp.setPageSize(pageSize);
        return resp;
    }

    @Test
    void listExportTasks_NoParams_ReturnsDefaultPageWithSliceName() throws Exception {
        ExportListResponse resp = buildListResponse(1, 20, 1,
                List.of(buildSummary("export-1", "slice-1", "route-1", "READY")));
        when(exportService.listExports(isNull(), isNull(), eq(1), eq(20))).thenReturn(resp);

        mockMvc.perform(get("/v1/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].export_id").value("export-1"))
                .andExpect(jsonPath("$.items[0].slice_id").value("slice-1"))
                .andExpect(jsonPath("$.items[0].slice_name").value("route-1"))
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[0].download_url").value("/v1/exports/export-1/download"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.page_size").value(20));

        verify(exportService).listExports(isNull(), isNull(), eq(1), eq(20));
    }

    @Test
    void listExportTasks_WithSliceIdAndStatus_AppliesFilters() throws Exception {
        ExportListResponse resp = buildListResponse(1, 20, 1,
                List.of(buildSummary("export-1", "slice-1", "route-1", "READY")));
        when(exportService.listExports(eq("slice-1"), eq("READY"), eq(1), eq(20))).thenReturn(resp);

        mockMvc.perform(get("/v1/exports")
                        .param("slice_id", "slice-1")
                        .param("status", "READY"))
                .andExpect(status().isOk());

        verify(exportService).listExports(eq("slice-1"), eq("READY"), eq(1), eq(20));
    }

    @Test
    void listExportTasks_Page2PageSize10_ReturnsPagedFields() throws Exception {
        ExportListResponse resp = buildListResponse(2, 10, 45, List.of());
        when(exportService.listExports(any(), any(), eq(2), eq(10))).thenReturn(resp);

        mockMvc.perform(get("/v1/exports").param("page", "2").param("page_size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.page_size").value(10))
                .andExpect(jsonPath("$.total").value(45));

        verify(exportService).listExports(isNull(), isNull(), eq(2), eq(10));
    }

    @Test
    void listExportTasks_PageSizeOver100_ClampedTo100() throws Exception {
        ExportListResponse resp = buildListResponse(1, 100, 0, List.of());
        when(exportService.listExports(any(), any(), eq(1), eq(100))).thenReturn(resp);

        mockMvc.perform(get("/v1/exports").param("page_size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page_size").value(100));

        verify(exportService).listExports(isNull(), isNull(), eq(1), eq(100));
    }

    @Test
    void listExportTasks_ZeroOrNegativePageParams_NormalizedToOne() throws Exception {
        ExportListResponse resp = buildListResponse(1, 1, 0, List.of());
        when(exportService.listExports(any(), any(), eq(1), eq(1))).thenReturn(resp);

        mockMvc.perform(get("/v1/exports").param("page", "0").param("page_size", "0"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/exports").param("page", "-3").param("page_size", "-2"))
                .andExpect(status().isOk());

        verify(exportService, times(2)).listExports(isNull(), isNull(), eq(1), eq(1));
    }

    @Test
    void listExportTasks_SliceNotFound_Returns404() throws Exception {
        when(exportService.listExports(any(), any(), anyInt(), anyInt()))
                .thenThrow(new SliceNotFoundException("invalid"));

        mockMvc.perform(get("/v1/exports").param("slice_id", "invalid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SLICE_NOT_FOUND"));
    }

    @Test
    void listExportTasks_InvalidStatus_Returns400() throws Exception {
        mockMvc.perform(get("/v1/exports").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));

        verify(exportService, never()).listExports(any(), any(), anyInt(), anyInt());
    }

    @Test
    void listExportTasks_ServiceError_Returns500() throws Exception {
        when(exportService.listExports(any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/v1/exports"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("EXPORT_QUERY_FAILED"));
    }

    @Test
    void listExportTasks_EmptyResult_ReturnsEmptyItems() throws Exception {
        ExportListResponse resp = buildListResponse(1, 20, 0, List.of());
        when(exportService.listExports(any(), any(), eq(1), eq(20))).thenReturn(resp);

        mockMvc.perform(get("/v1/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }
}
