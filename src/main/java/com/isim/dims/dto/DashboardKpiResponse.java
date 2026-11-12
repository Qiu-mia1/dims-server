package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 仪表盘核心指标卡响应
 *
 * 对应 GET /v1/dashboard/kpi 的响应。
 * 数据源：slice_stats 聚合 + data_slice 计数（status='READY'）。
 * @feature: dashboard
 */
@Data
public class DashboardKpiResponse {

    /** 5 个核心 KPI 卡片 */
    private List<DashboardKpiCard> kpiCards;

    /** 统计更新时间，UTC RFC3339 */
    private String updatedAt;
}
