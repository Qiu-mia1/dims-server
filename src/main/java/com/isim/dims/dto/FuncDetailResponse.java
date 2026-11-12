package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 单功能详情响应（中间核心区）
 *
 * 对应 GET /v1/dashboard/func-detail 的响应。
 * 包含 4 个功能 KPI 卡（激活次数/运行里程/平均时长/接管介入次数，
 * 按 function 参数读取不同 slice_stats 列）+ 6 个通用指标小卡。
 * @feature: dashboard
 */
@Data
public class FuncDetailResponse {

    /** 功能类型：NOA | AEB | APA | LCC | ACC */
    private String function;

    /** 4 个功能 KPI 卡片 */
    private List<DashboardKpiCard> kpiCards;

    /** 6 个通用指标小卡（2×3 网格） */
    private List<IndicatorCard> indicatorCards;

    /**
     * 指标小卡项
     */
    @Data
    public static class IndicatorCard {

        /** 指标键，如 speed_avg / gnss_quality */
        private String key;

        /** 展示标题，如 "车辆速度" */
        private String title;

        /** 指标值（数值或字符串；无数据时为 0 / null / "未知"） */
        private Object value;

        /** 单位（无单位指标为 null） */
        private String unit;

        /** 状态：good | fair | poor | normal（无状态时为 null） */
        private String status;
    }
}
