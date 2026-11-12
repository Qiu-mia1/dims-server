package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MXF 看板完整响应：聚合 KPI / 数据包总量分布 / 特殊事件通知。
 *
 * <p>前端一次性拉到完整看板数据，避免多次请求。
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfDashboardSummaryResponse {

    /**
     * 当前时间筛选范围：today|week|month|year|all|custom（custom 表示按请求的 timeStart/timeEnd 自定义范围）
     */
    private String timeRange;

    /**
     * 顶部 KPI 卡片
     */
    private MxfKpiCardsResponse kpi;

    /**
     * 数据包总量柱状图：时间范围内 data_slice 按 event_name 分类的切片数量（Top N + 其他）
     */
    private List<MxfEventDistributionItem> eventNameDistribution;

    /**
     * 特殊事件通知：data_slice 中最近 24 小时命中 6 类特殊 event_name 的切片（最新在前）
     */
    private List<MxfSpecialEventAlert> specialEventAlerts;

    /**
     * 特殊事件总数（当前时间筛选范围内）
     */
    private Long specialEventTotal;
}
