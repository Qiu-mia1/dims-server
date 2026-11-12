package com.isim.dims.controller;

import com.isim.dims.dto.DashboardAlertsResponse;
import com.isim.dims.dto.DashboardDistributionResponse;
import com.isim.dims.dto.DashboardKpiResponse;
import com.isim.dims.dto.DashboardStatsResponse;
import com.isim.dims.dto.DashboardTrendsResponse;
import com.isim.dims.dto.FuncDetailResponse;
import com.isim.dims.dto.FuncPerfResponse;
import com.isim.dims.dto.SceneDistResponse;
import com.isim.dims.security.SliceSecurityTestConfig;
import com.isim.dims.dto.TakeoverReasonsResponse;
import com.isim.dims.dto.Top5Response;
import com.isim.dims.dto.VehicleTypesResponse;
import com.isim.dims.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DashboardController.class)
@Import(SliceSecurityTestConfig.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    // ============ GET /v1/dashboard/stats ============

    @Test
    void getStats_Returns200() throws Exception {
        DashboardStatsResponse resp = new DashboardStatsResponse();
        resp.setTotalSlices(100L);
        resp.setTodayUploads(5L);
        resp.setTotalDataSize(80_000_000_000L);
        resp.setSensorDistribution(List.of());

        when(dashboardService.getStats()).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_slices").value(100))
                .andExpect(jsonPath("$.today_uploads").value(5))
                .andExpect(jsonPath("$.total_data_size").value(80000000000L));
    }

    @Test
    void getStats_ServiceError_Returns500() throws Exception {
        when(dashboardService.getStats()).thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(get("/v1/dashboard/stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_STATS_FAILED"))
                .andExpect(jsonPath("$.error.message").exists());
    }

    // ============ GET /v1/dashboard/recent-uploads ============

    @Test
    void getRecentUploads_Returns200() throws Exception {
        mockMvc.perform(get("/v1/dashboard/recent-uploads"))
                .andExpect(status().isOk());
    }

    // ============ GET /v1/dashboard/distribution ============

    @Test
    void getDistribution_WithValidDimension_Returns200() throws Exception {
        DashboardDistributionResponse resp = new DashboardDistributionResponse();
        resp.setDimension("source_type");
        resp.setItems(List.of());

        when(dashboardService.getDistribution("source_type")).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/distribution")
                        .param("dimension", "source_type"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimension").value("source_type"));
    }

    @Test
    void getDistribution_InvalidDimension_Returns400() throws Exception {
        when(dashboardService.getDistribution("invalid"))
                .thenThrow(new IllegalArgumentException("Unsupported dimension: invalid"));

        mockMvc.perform(get("/v1/dashboard/distribution")
                        .param("dimension", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DIMENSION"));
    }

    @Test
    void getDistribution_WithoutDimension_Returns400() throws Exception {
        mockMvc.perform(get("/v1/dashboard/distribution"))
                .andExpect(status().isBadRequest());
    }

    // ============ GET /v1/dashboard/alerts ============

    @Test
    void getAlerts_Returns200() throws Exception {
        DashboardAlertsResponse resp = new DashboardAlertsResponse();
        resp.setAlerts(List.of());

        when(dashboardService.getAlerts()).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts").isArray());
    }

    @Test
    void getAlerts_ServiceError_Returns500() throws Exception {
        when(dashboardService.getAlerts()).thenThrow(new RuntimeException("Alert query failed"));

        mockMvc.perform(get("/v1/dashboard/alerts"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_ALERTS_FAILED"));
    }

    // ============ GET /v1/dashboard/trends ============

    @Test
    void getTrends_WithDaysParam_Returns200() throws Exception {
        DashboardTrendsResponse resp = new DashboardTrendsResponse();
        resp.setTrends(List.of());

        when(dashboardService.getTrends(30)).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/trends")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends").isArray());
    }

    @Test
    void getTrends_DefaultDays_Returns200() throws Exception {
        DashboardTrendsResponse resp = new DashboardTrendsResponse();
        resp.setTrends(List.of());

        when(dashboardService.getTrends(30)).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends").isArray());
    }

    @Test
    void getTrends_ServiceError_Returns500() throws Exception {
        when(dashboardService.getTrends(anyInt())).thenThrow(new RuntimeException("Trend query failed"));

        mockMvc.perform(get("/v1/dashboard/trends"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_TRENDS_FAILED"));
    }

    // ============ GET /v1/dashboard/kpi ============

    @Test
    void getKpi_Returns200() throws Exception {
        DashboardKpiResponse resp = new DashboardKpiResponse();
        resp.setKpiCards(List.of());

        when(dashboardService.getKpi(any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/kpi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpi_cards").isArray());
    }

    @Test
    void getKpi_ServiceError_Returns500WithGenericMessage() throws Exception {
        when(dashboardService.getKpi(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("java.sql.SQLSyntaxErrorException: broken query"));

        mockMvc.perform(get("/v1/dashboard/kpi"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_KPI_FAILED"))
                // W2: 500 不暴露内部异常消息
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }

    // ============ GET /v1/dashboard/vehicle-types ============

    @Test
    void getVehicleTypes_Returns200() throws Exception {
        VehicleTypesResponse resp = new VehicleTypesResponse();
        resp.setTypes(List.of());

        when(dashboardService.getVehicleTypes(any(), any())).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/vehicle-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").isArray());
    }

    @Test
    void getVehicleTypes_ServiceError_Returns500WithGenericMessage() throws Exception {
        when(dashboardService.getVehicleTypes(any(), any())).thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get("/v1/dashboard/vehicle-types"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_VEHICLE_TYPES_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }

    // ============ GET /v1/dashboard/scene-dist ============

    @Test
    void getSceneDist_Returns200() throws Exception {
        SceneDistResponse resp = new SceneDistResponse();
        resp.setItems(List.of());

        when(dashboardService.getSceneDist(any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/scene-dist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void getSceneDist_ServiceError_Returns500WithGenericMessage() throws Exception {
        when(dashboardService.getSceneDist(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get("/v1/dashboard/scene-dist"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_SCENE_DIST_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }

    // ============ GET /v1/dashboard/func-perf ============

    @Test
    void getFuncPerf_Returns200() throws Exception {
        FuncPerfResponse resp = new FuncPerfResponse();
        resp.setFunctions(List.of());

        when(dashboardService.getFuncPerf(any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/func-perf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functions").isArray());
    }

    @Test
    void getFuncPerf_ServiceError_Returns500WithGenericMessage() throws Exception {
        when(dashboardService.getFuncPerf(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get("/v1/dashboard/func-perf"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_FUNC_PERF_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }

    // ============ GET /v1/dashboard/func-detail ============

    @Test
    void getFuncDetail_WithFunction_Returns200() throws Exception {
        FuncDetailResponse resp = new FuncDetailResponse();
        resp.setFunction("NOA");
        resp.setKpiCards(List.of());
        resp.setIndicatorCards(List.of());

        when(dashboardService.getFuncDetail(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/func-detail")
                        .param("function", "NOA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.function").value("NOA"));
    }

    @Test
    void getFuncDetail_InvalidFunction_Returns400() throws Exception {
        when(dashboardService.getFuncDetail(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unsupported function: XXX"));

        mockMvc.perform(get("/v1/dashboard/func-detail")
                        .param("function", "XXX"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FUNCTION"));
    }

    @Test
    void getFuncDetail_ServiceError_Returns500WithGenericMessage() throws Exception {
        when(dashboardService.getFuncDetail(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db connection refused"));

        mockMvc.perform(get("/v1/dashboard/func-detail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_FUNC_DETAIL_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }

    // ============ GET /v1/dashboard/takeover ============

    @Test
    void getTakeover_Returns200() throws Exception {
        TakeoverReasonsResponse resp = new TakeoverReasonsResponse();
        resp.setItems(List.of());

        when(dashboardService.getTakeoverReasons(any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/takeover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void getTakeover_ServiceError_Returns500WithGenericMessage() throws Exception {
        when(dashboardService.getTakeoverReasons(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get("/v1/dashboard/takeover"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_TAKEOVER_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }

    // ============ GET /v1/dashboard/top5 ============

    @Test
    void getTop5_Returns200() throws Exception {
        Top5Response resp = new Top5Response();
        resp.setItems(List.of());

        when(dashboardService.getTop5(any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        mockMvc.perform(get("/v1/dashboard/top5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void getTop5_ServiceError_Returns500WithGenericMessage() throws Exception {
        when(dashboardService.getTop5(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get("/v1/dashboard/top5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("DASHBOARD_TOP5_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Internal server error"));
    }
}
