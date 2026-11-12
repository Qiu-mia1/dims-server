package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 工况/场景 TOP5 排行榜响应
 *
 * 对应 GET /v1/dashboard/top5 的响应。
 * 数据源：slice_stats.scene_breakdown JSON 列（{scene_label: count}）逐行解析后
 * 聚合排序取 TOP5。success_rate 当前无数据源，返回 null。无数据时 items 为空数组。
 * @feature: dashboard
 */
@Data
public class Top5Response {

    /** TOP5 列表（按触发次数降序，最多 5 项） */
    private List<TopItem> items;

    /**
     * 排行项
     */
    @Data
    public static class TopItem {

        /** 排名，从 1 开始 */
        private Integer rank;

        /** 场景/工况名称 */
        private String scenarioName;

        /** 触发次数 */
        private Long triggerCount;

        /** 成功率（%），当前无数据源，恒为 null */
        private Double successRate;
    }
}
