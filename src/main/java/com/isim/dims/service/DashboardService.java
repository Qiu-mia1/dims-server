package com.isim.dims.service;

import com.isim.dims.dto.DashboardAlertsResponse;
import com.isim.dims.dto.DashboardDistributionResponse;
import com.isim.dims.dto.DashboardKpiResponse;
import com.isim.dims.dto.DashboardStatsResponse;
import com.isim.dims.dto.DashboardTrendsResponse;
import com.isim.dims.dto.FuncDetailResponse;
import com.isim.dims.dto.FuncPerfResponse;
import com.isim.dims.dto.RecentUploadsResponse;
import com.isim.dims.dto.SceneDistResponse;
import com.isim.dims.dto.TakeoverReasonsResponse;
import com.isim.dims.dto.Top5Response;
import com.isim.dims.dto.VehicleTypesResponse;

import java.util.List;

/**
 * 仪表盘 Service 接口
 *
 * 负责提供仪表盘所需的统计数据：
 * - 系统整体统计概览（切片总数、今日上传、总数据量、传感器分布）
 * - 最近上传列表
 * - 按维度分布统计
 * - 质量告警
 * - 时间趋势
 * - 重构版驾驶舱：核心 KPI / 车型选项 / 场景分布 / 功能×场景表现 /
 *   单功能详情 / 接管原因分布 / 工况 TOP5（数据源 slice_stats + data_slice）
 * @feature: dashboard
 */
public interface DashboardService {

    /**
     * 获取仪表盘统计概览
     *
     * @return DashboardStatsResponse
     */
    DashboardStatsResponse getStats();

    /**
     * 获取最近上传列表（分页）
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数
     * @return RecentUploadsResponse
     */
    RecentUploadsResponse getRecentUploads(int page, int pageSize);

    /**
     * 按维度返回分布统计
     *
     * @param dimension 维度：source_type | project | car_type | event_name
     * @return DashboardDistributionResponse
     */
    DashboardDistributionResponse getDistribution(String dimension);

    /**
     * 返回质量告警列表
     *
     * 告警类型：
     *   missing_calibration  — CFDI 链路切片（cfdi_meta 有记录）但 camera_calibration 表无记录
     *   vin_empty            — cfdi_meta.vin 为空
     *   incomplete_sensors   — sensor_stream 数 < 8
     *   status_error         — data_slice.status = 'ERROR'
     *
     * @return DashboardAlertsResponse
     */
    DashboardAlertsResponse getAlerts();

    /**
     * 返回每日上传数量和数据量趋势
     *
     * @param days 天数，默认 30
     * @return DashboardTrendsResponse
     */
    DashboardTrendsResponse getTrends(int days);

    /**
     * 获取 5 个核心 KPI 卡片 + 环比趋势
     *
     * 采集里程 SUM(vehicle_mileage_delta)、数据包数量 COUNT(data_slice.id, status='READY')、
     * NOA成功率 AVG(noa_success_rate)、AEB介入率 SUM(aeb_trigger_count)/SUM(aeb_total_scenes)、
     * APA成功率 AVG(apa_success_rate)。
     * 环比：当前周期 vs 上一周期（today→昨天同时段 / week→上周 / month→上月 / year→去年；
     * 自定义 time_start/time_end 则对比等长前序窗口），返回差值百分比 + up/down 方向。
     *
     * @param timePreset   快捷时间：today | week | month | year（可选）
     * @param timeStart    自定义起始，RFC3339（可选）
     * @param timeEnd      自定义结束，RFC3339（可选）
     * @param vehicleTypes 车型过滤列表（可选）
     * @param sliceId      选中单数据包（可选）
     * @param vin          VIN 码模糊匹配（cfdi_meta.vin 或文件名，可选）
     * @param utcDiffUs    UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，可选）
     * @return DashboardKpiResponse
     */
    DashboardKpiResponse getKpi(String timePreset, String timeStart, String timeEnd,
                                List<String> vehicleTypes, String sliceId,
                                String vin, Long utcDiffUs);

    /**
     * 获取车型下拉选项（slice_stats.vehicle_type DISTINCT，可按 vin / utc_diff_us 过滤）
     *
     * @param vin       VIN 码模糊匹配（cfdi_meta.vin 或文件名，可选）
     * @param utcDiffUs UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，可选）
     * @return VehicleTypesResponse
     */
    VehicleTypesResponse getVehicleTypes(String vin, Long utcDiffUs);

    /**
     * 获取场景类型分布（slice_stats.road_scene GROUP BY）
     *
     * @param timePreset   快捷时间：today | week | month | year（可选）
     * @param timeStart    自定义起始，RFC3339（可选）
     * @param timeEnd      自定义结束，RFC3339（可选）
     * @param vehicleTypes 车型过滤列表（可选）
     * @param sliceId      选中单数据包（可选）
     * @param vin          VIN 码模糊匹配（cfdi_meta.vin 或文件名，可选）
     * @param utcDiffUs    UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，可选）
     * @return SceneDistResponse
     */
    SceneDistResponse getSceneDist(String timePreset, String timeStart, String timeEnd,
                                   List<String> vehicleTypes, String sliceId,
                                   String vin, Long utcDiffUs);

    /**
     * 获取功能表现 × 场景交叉分析（3 个功能 × road_scene）
     *
     * APA成功率 AVG(apa_success_rate)、NOA接管率 SUM(takeover_count)/COUNT(*)、
     * AEB介入率 SUM(aeb_trigger_count)/SUM(aeb_total_scenes)，均按 road_scene 分组。
     *
     * @param timePreset   快捷时间：today | week | month | year（可选）
     * @param timeStart    自定义起始，RFC3339（可选）
     * @param timeEnd      自定义结束，RFC3339（可选）
     * @param vehicleTypes 车型过滤列表（可选）
     * @param sliceId      选中单数据包（可选）
     * @param vin          VIN 码模糊匹配（cfdi_meta.vin 或文件名，可选）
     * @param utcDiffUs    UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，可选）
     * @return FuncPerfResponse
     */
    FuncPerfResponse getFuncPerf(String timePreset, String timeStart, String timeEnd,
                                 List<String> vehicleTypes, String sliceId,
                                 String vin, Long utcDiffUs);

    /**
     * 获取单功能详情（4 个功能 KPI 卡 + 6 个通用指标小卡，含环比）
     *
     * @param function     功能类型：NOA | AEB | APA | LCC | ACC（缺省默认 NOA）
     * @param timePreset   快捷时间：today | week | month | year（可选）
     * @param timeStart    自定义起始，RFC3339（可选）
     * @param timeEnd      自定义结束，RFC3339（可选）
     * @param vehicleTypes 车型过滤列表（可选）
     * @param sliceId      选中单数据包（可选）
     * @param vin          VIN 码模糊匹配（cfdi_meta.vin 或文件名，可选）
     * @param utcDiffUs    UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，可选）
     * @return FuncDetailResponse
     */
    FuncDetailResponse getFuncDetail(String function, String timePreset, String timeStart, String timeEnd,
                                     List<String> vehicleTypes, String sliceId,
                                     String vin, Long utcDiffUs);

    /**
     * 获取接管/介入原因分布（slice_stats.takeover_reasons JSON 解析后聚合）
     *
     * @param timePreset   快捷时间：today | week | month | year（可选）
     * @param timeStart    自定义起始，RFC3339（可选）
     * @param timeEnd      自定义结束，RFC3339（可选）
     * @param vehicleTypes 车型过滤列表（可选）
     * @param sliceId      选中单数据包（可选）
     * @param vin          VIN 码模糊匹配（cfdi_meta.vin 或文件名，可选）
     * @param utcDiffUs    UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，可选）
     * @return TakeoverReasonsResponse
     */
    TakeoverReasonsResponse getTakeoverReasons(String timePreset, String timeStart, String timeEnd,
                                               List<String> vehicleTypes, String sliceId,
                                               String vin, Long utcDiffUs);

    /**
     * 获取工况/场景 TOP5 排行榜（slice_stats.scene_breakdown JSON 解析后聚合排序）
     *
     * @param timePreset   快捷时间：today | week | month | year（可选）
     * @param timeStart    自定义起始，RFC3339（可选）
     * @param timeEnd      自定义结束，RFC3339（可选）
     * @param vehicleTypes 车型过滤列表（可选）
     * @param sliceId      选中单数据包（可选）
     * @param vin          VIN 码模糊匹配（cfdi_meta.vin 或文件名，可选）
     * @param utcDiffUs    UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，可选）
     * @return Top5Response
     */
    Top5Response getTop5(String timePreset, String timeStart, String timeEnd,
                         List<String> vehicleTypes, String sliceId,
                         String vin, Long utcDiffUs);
}
