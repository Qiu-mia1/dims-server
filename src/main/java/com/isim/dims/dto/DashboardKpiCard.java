package com.isim.dims.dto;

import lombok.Data;

/**
 * 仪表盘 KPI 卡片（核心指标卡 & 功能详情 KPI 卡共用）
 *
 * 对应 GET /v1/dashboard/kpi 与 GET /v1/dashboard/func-detail 响应中的
 * kpi_cards 数组元素。
 * 空值策略：value 无数据时为 0；trend_pct/trend_direction 在上一周期
 * 无数据（或为 0）时返回 null，前端显示 "--"。
 * @feature: dashboard
 */
@Data
public class DashboardKpiCard {

    /** 指标键，如 total_mileage_km / activation_count */
    private String key;

    /** 展示标题，如 "采集里程(km)" */
    private String title;

    /** 当前值，无数据时为 0 */
    private double value;

    /** 单位，如 km / 个 / % / 次 / min */
    private String unit;

    /** 环比差值百分比（相对上一周期，保留 1 位小数；不可计算时为 null） */
    private Double trendPct;

    /** 趋势方向：up | down（与上一周期持平或无上一周期时为 null） */
    private String trendDirection;
}
