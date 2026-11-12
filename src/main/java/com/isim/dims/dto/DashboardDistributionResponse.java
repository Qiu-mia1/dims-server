package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 仪表盘分布统计响应
 *
 * 对应 GET /v1/dashboard/distribution 的响应。
 * 按 source_type / project / car_type / event_name 维度返回分布统计。
 * @feature: dashboard
 */
@Data
public class DashboardDistributionResponse {

    /** 分布维度：source_type | project | car_type | event_name */
    private String dimension;

    /** 分布项列表 */
    private List<DistributionItem> items;

    @Data
    public static class DistributionItem {

        /** 维度值，如 "CFDI"、"LUS" */
        private String label;

        /** 计数 */
        private Long count;

        /** 占比（%），如 83.3 */
        private Double percentage;
    }
}
