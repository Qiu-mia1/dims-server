package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 接管/介入原因分布响应（环形饼图）
 *
 * 对应 GET /v1/dashboard/takeover 的响应。
 * 数据源：slice_stats.takeover_reasons JSON 列（{reason: count}）逐行解析后聚合，
 * 按 count 降序排列。无数据时 items 为空数组。
 * @feature: dashboard
 */
@Data
public class TakeoverReasonsResponse {

    /** 原因分布列表（按次数降序） */
    private List<ReasonItem> items;

    /**
     * 原因分布项
     */
    @Data
    public static class ReasonItem {

        /** 原因键（Python 侧写入的原始字符串，如 map_mismatch） */
        private String reason;

        /** 出现次数 */
        private Long count;

        /** 占比（%），保留 1 位小数 */
        private Double percentage;
    }
}
