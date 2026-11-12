package com.isim.dims.controller;

import com.isim.dims.dto.DashboardAlertsResponse;
import com.isim.dims.dto.DashboardDistributionResponse;
import com.isim.dims.dto.DashboardKpiResponse;
import com.isim.dims.dto.DashboardStatsResponse;
import com.isim.dims.dto.DashboardTrendsResponse;
import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.FuncDetailResponse;
import com.isim.dims.dto.FuncPerfResponse;
import com.isim.dims.dto.RecentUploadsResponse;
import com.isim.dims.dto.SceneDistResponse;
import com.isim.dims.dto.TakeoverReasonsResponse;
import com.isim.dims.dto.Top5Response;
import com.isim.dims.dto.VehicleTypesResponse;
import com.isim.dims.service.DashboardService;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 仪表盘 Controller
 *
 * 负责首页概览数据展示：
 * - 统计概览（切片总数、今日上传、总数据量、传感器分布）
 * - 最近上传列表
 * - 按维度分布统计（来源/项目/车型/事件名）
 * - 质量告警
 * - 时间趋势
 *
 * 对应 OpenAPI paths:
 *   GET /v1/dashboard/stats
 *   GET /v1/dashboard/recent-uploads
 *   GET /v1/dashboard/distribution
 *   GET /v1/dashboard/alerts
 *   GET /v1/dashboard/trends
 *   GET /v1/dashboard/kpi               （重构版：5 个核心 KPI + 环比）
 *   GET /v1/dashboard/vehicle-types     （重构版：车型下拉选项）
 *   GET /v1/dashboard/scene-dist        （重构版：场景类型分布）
 *   GET /v1/dashboard/func-perf         （重构版：功能×场景交叉分析）
 *   GET /v1/dashboard/func-detail       （重构版：单功能详情）
 *   GET /v1/dashboard/takeover          （重构版：接管原因分布）
 *   GET /v1/dashboard/top5              （重构版：工况 TOP5）
 * @feature: dashboard
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 仪表盘统计概览
     *
     * GET /v1/dashboard/stats
     * 返回系统整体统计数据。
     *
     * @return 200 + 统计概览
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getDashboardStats request, traceId={}", traceId);
        try {
            DashboardStatsResponse response = dashboardService.getStats();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getDashboardStats failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_STATS_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 最近上传列表
     *
     * GET /v1/dashboard/recent-uploads
     * 返回最近上传的数据切片列表（分页，默认 10 条）。
     *
     * @param page     页码，默认 1
     * @param pageSize 每页条数，默认 10，最大 50
     * @return 200 + 最近上传列表（分页）
     */
    @GetMapping("/recent-uploads")
    public ResponseEntity<?> getRecentUploads(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getRecentUploads request: page={}, pageSize={}, traceId={}", page, pageSize, traceId);
        try {
            RecentUploadsResponse response = dashboardService.getRecentUploads(page, pageSize);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getRecentUploads failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_RECENT_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 按维度分布统计
     *
     * GET /v1/dashboard/distribution?dimension=source_type
     * 返回指定维度的分布统计（count + percentage）。
     *
     * @param dimension 分布维度：source_type | project | car_type | event_name
     * @return 200 + 分布统计
     */
    @GetMapping("/distribution")
    public ResponseEntity<?> getDistribution(
            @RequestParam("dimension") String dimension,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getDistribution request: dimension={}, traceId={}", dimension, traceId);
        try {
            DashboardDistributionResponse response = dashboardService.getDistribution(dimension);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("getDistribution invalid dimension: {}", dimension);
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("INVALID_DIMENSION", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("getDistribution failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_DISTRIBUTION_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 质量告警列表
     *
     * GET /v1/dashboard/alerts
     * 返回质量告警列表（标定缺失、VIN 为空、传感器不完整、处理失败）。
     *
     * @return 200 + 告警列表
     */
    @GetMapping("/alerts")
    public ResponseEntity<?> getAlerts(
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getAlerts request, traceId={}", traceId);
        try {
            DashboardAlertsResponse response = dashboardService.getAlerts();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getAlerts failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_ALERTS_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 时间趋势
     *
     * GET /v1/dashboard/trends?days=30
     * 返回每日上传数量和数据量趋势。
     *
     * @param days 天数，默认 30，最大 365
     * @return 200 + 趋势数据
     */
    @GetMapping("/trends")
    public ResponseEntity<?> getTrends(
            @RequestParam(value = "days", defaultValue = "30") @Max(365) int days,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getTrends request: days={}, traceId={}", days, traceId);
        try {
            DashboardTrendsResponse response = dashboardService.getTrends(days);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getTrends failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_TRENDS_FAILED", e.getMessage(), traceId));
        }
    }

    // ========================================================================
    // 重构版驾驶舱接口（数据源：slice_stats + data_slice）
    // 统一 Query 参数：time_preset / time_start / time_end / vehicle_types / slice_id
    // ========================================================================

    /**
     * 5 个核心 KPI 卡片 + 环比趋势
     *
     * GET /v1/dashboard/kpi
     * 数据包数量仅统计 status='READY'；环比对比上一周期（差值百分比 + up/down 方向）。
     *
     * @param timePreset   快捷时间：today | week | month | year
     * @param timeStart    自定义起始，RFC3339（可选）
     * @param timeEnd      自定义结束，RFC3339（可选）
     * @param vehicleTypes 车型过滤，逗号分隔（可选）
     * @param sliceId      选中单数据包（可选）
     * @param vin          VIN 码模糊匹配（可选）
     * @param utcDiffUs    UTC 偏移微秒值精确匹配（可选）
     * @return 200 + 5 个 KPI 卡片
     */
    @GetMapping("/kpi")
    public ResponseEntity<?> getKpi(
            @RequestParam(value = "time_preset", required = false) String timePreset,
            @RequestParam(value = "time_start", required = false) String timeStart,
            @RequestParam(value = "time_end", required = false) String timeEnd,
            @RequestParam(value = "vehicle_types", required = false) String vehicleTypes,
            @RequestParam(value = "slice_id", required = false) String sliceId,
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "utc_diff_us", required = false) Long utcDiffUs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getKpi request: timePreset={}, timeStart={}, timeEnd={}, vehicleTypes={}, sliceId={}, vin={}, utcDiffUs={}, traceId={}",
                timePreset, timeStart, timeEnd, vehicleTypes, sliceId, vin, utcDiffUs, traceId);
        try {
            DashboardKpiResponse response = dashboardService.getKpi(
                    timePreset, timeStart, timeEnd, parseVehicleTypes(vehicleTypes), sliceId, vin, utcDiffUs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getKpi failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_KPI_FAILED", "Internal server error", traceId));
        }
    }

    /**
     * 车型下拉选项
     *
     * GET /v1/dashboard/vehicle-types
     * 数据源：slice_stats.vehicle_type DISTINCT（非空），可按 vin / utc_diff_us 过滤。
     *
     * @param vin       VIN 码模糊匹配（可选）
     * @param utcDiffUs UTC 偏移微秒值精确匹配（可选）
     * @return 200 + 车型选项列表
     */
    @GetMapping("/vehicle-types")
    public ResponseEntity<?> getVehicleTypes(
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "utc_diff_us", required = false) Long utcDiffUs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getVehicleTypes request: vin={}, utcDiffUs={}, traceId={}", vin, utcDiffUs, traceId);
        try {
            VehicleTypesResponse response = dashboardService.getVehicleTypes(vin, utcDiffUs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getVehicleTypes failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_VEHICLE_TYPES_FAILED", "Internal server error", traceId));
        }
    }

    /**
     * 场景类型分布（横向柱状图）
     *
     * GET /v1/dashboard/scene-dist
     * 数据源：slice_stats.road_scene GROUP BY；枚举映射 URBAN→城市 等，null/未匹配→其他。
     *
     * @return 200 + 场景分布列表
     */
    @GetMapping("/scene-dist")
    public ResponseEntity<?> getSceneDist(
            @RequestParam(value = "time_preset", required = false) String timePreset,
            @RequestParam(value = "time_start", required = false) String timeStart,
            @RequestParam(value = "time_end", required = false) String timeEnd,
            @RequestParam(value = "vehicle_types", required = false) String vehicleTypes,
            @RequestParam(value = "slice_id", required = false) String sliceId,
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "utc_diff_us", required = false) Long utcDiffUs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getSceneDist request: timePreset={}, vehicleTypes={}, sliceId={}, vin={}, utcDiffUs={}, traceId={}",
                timePreset, vehicleTypes, sliceId, vin, utcDiffUs, traceId);
        try {
            SceneDistResponse response = dashboardService.getSceneDist(
                    timePreset, timeStart, timeEnd, parseVehicleTypes(vehicleTypes), sliceId, vin, utcDiffUs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getSceneDist failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_SCENE_DIST_FAILED", "Internal server error", traceId));
        }
    }

    /**
     * 功能表现 × 场景交叉分析（横向柱状图）
     *
     * GET /v1/dashboard/func-perf
     * 3 个功能（APA成功率/NOA接管率/AEB介入率）× road_scene 分组聚合，缺失场景补 0。
     *
     * @return 200 + 功能表现列表
     */
    @GetMapping("/func-perf")
    public ResponseEntity<?> getFuncPerf(
            @RequestParam(value = "time_preset", required = false) String timePreset,
            @RequestParam(value = "time_start", required = false) String timeStart,
            @RequestParam(value = "time_end", required = false) String timeEnd,
            @RequestParam(value = "vehicle_types", required = false) String vehicleTypes,
            @RequestParam(value = "slice_id", required = false) String sliceId,
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "utc_diff_us", required = false) Long utcDiffUs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getFuncPerf request: timePreset={}, vehicleTypes={}, sliceId={}, vin={}, utcDiffUs={}, traceId={}",
                timePreset, vehicleTypes, sliceId, vin, utcDiffUs, traceId);
        try {
            FuncPerfResponse response = dashboardService.getFuncPerf(
                    timePreset, timeStart, timeEnd, parseVehicleTypes(vehicleTypes), sliceId, vin, utcDiffUs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getFuncPerf failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_FUNC_PERF_FAILED", "Internal server error", traceId));
        }
    }

    /**
     * 单功能详情（中间核心区：4 个功能 KPI 卡 + 6 个通用指标小卡）
     *
     * GET /v1/dashboard/func-detail?function=NOA
     * function 缺省默认 NOA；非法值 → 400 INVALID_FUNCTION。
     *
     * @param function 功能类型：NOA | AEB | APA | LCC | ACC
     * @return 200 + 功能详情；400 + INVALID_FUNCTION
     */
    @GetMapping("/func-detail")
    public ResponseEntity<?> getFuncDetail(
            @RequestParam(value = "function", required = false) String function,
            @RequestParam(value = "time_preset", required = false) String timePreset,
            @RequestParam(value = "time_start", required = false) String timeStart,
            @RequestParam(value = "time_end", required = false) String timeEnd,
            @RequestParam(value = "vehicle_types", required = false) String vehicleTypes,
            @RequestParam(value = "slice_id", required = false) String sliceId,
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "utc_diff_us", required = false) Long utcDiffUs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getFuncDetail request: function={}, timePreset={}, vehicleTypes={}, sliceId={}, vin={}, utcDiffUs={}, traceId={}",
                function, timePreset, vehicleTypes, sliceId, vin, utcDiffUs, traceId);
        try {
            FuncDetailResponse response = dashboardService.getFuncDetail(
                    function, timePreset, timeStart, timeEnd, parseVehicleTypes(vehicleTypes), sliceId, vin, utcDiffUs);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("getFuncDetail invalid function: {}", function);
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("INVALID_FUNCTION", e.getMessage(), traceId));
        } catch (Exception e) {
            log.error("getFuncDetail failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_FUNC_DETAIL_FAILED", "Internal server error", traceId));
        }
    }

    /**
     * 接管/介入原因分布（环形饼图）
     *
     * GET /v1/dashboard/takeover
     * 数据源：slice_stats.takeover_reasons JSON 列逐行解析聚合；无数据 → 空数组。
     *
     * @return 200 + 接管原因分布列表
     */
    @GetMapping("/takeover")
    public ResponseEntity<?> getTakeover(
            @RequestParam(value = "time_preset", required = false) String timePreset,
            @RequestParam(value = "time_start", required = false) String timeStart,
            @RequestParam(value = "time_end", required = false) String timeEnd,
            @RequestParam(value = "vehicle_types", required = false) String vehicleTypes,
            @RequestParam(value = "slice_id", required = false) String sliceId,
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "utc_diff_us", required = false) Long utcDiffUs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getTakeover request: timePreset={}, vehicleTypes={}, sliceId={}, vin={}, utcDiffUs={}, traceId={}",
                timePreset, vehicleTypes, sliceId, vin, utcDiffUs, traceId);
        try {
            TakeoverReasonsResponse response = dashboardService.getTakeoverReasons(
                    timePreset, timeStart, timeEnd, parseVehicleTypes(vehicleTypes), sliceId, vin, utcDiffUs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getTakeover failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_TAKEOVER_FAILED", "Internal server error", traceId));
        }
    }

    /**
     * 工况/场景 TOP5 排行榜
     *
     * GET /v1/dashboard/top5
     * 数据源：slice_stats.scene_breakdown JSON 列逐行解析聚合排序取 TOP5；
     * 无数据 → 空数组。
     *
     * @return 200 + TOP5 列表
     */
    @GetMapping("/top5")
    public ResponseEntity<?> getTop5(
            @RequestParam(value = "time_preset", required = false) String timePreset,
            @RequestParam(value = "time_start", required = false) String timeStart,
            @RequestParam(value = "time_end", required = false) String timeEnd,
            @RequestParam(value = "vehicle_types", required = false) String vehicleTypes,
            @RequestParam(value = "slice_id", required = false) String sliceId,
            @RequestParam(value = "vin", required = false) String vin,
            @RequestParam(value = "utc_diff_us", required = false) Long utcDiffUs,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getTop5 request: timePreset={}, vehicleTypes={}, sliceId={}, vin={}, utcDiffUs={}, traceId={}",
                timePreset, vehicleTypes, sliceId, vin, utcDiffUs, traceId);
        try {
            Top5Response response = dashboardService.getTop5(
                    timePreset, timeStart, timeEnd, parseVehicleTypes(vehicleTypes), sliceId, vin, utcDiffUs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("getTop5 failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("DASHBOARD_TOP5_FAILED", "Internal server error", traceId));
        }
    }

    /**
     * 解析 vehicle_types 逗号分隔参数 → 车型列表（去空、去重、保序）
     */
    private List<String> parseVehicleTypes(String vehicleTypes) {
        if (vehicleTypes == null || vehicleTypes.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String type : Arrays.asList(vehicleTypes.split(","))) {
            String trimmed = type.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
