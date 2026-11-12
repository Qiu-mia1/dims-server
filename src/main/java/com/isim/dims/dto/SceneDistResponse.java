package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 场景类型分布响应（横向柱状图）
 *
 * 对应 GET /v1/dashboard/scene-dist 的响应。
 * 枚举映射：URBAN→城市, HIGHWAY→高速, TUNNEL→隧道,
 * TOLL_GATE→收费站, ROUNDABOUT→环路, null/未匹配→其他（合并为一项）。
 * @feature: dashboard
 */
@Data
public class SceneDistResponse {

    /** 场景分布列表（按数量降序） */
    private List<SceneItem> items;

    /**
     * 场景分布项
     */
    @Data
    public static class SceneItem {

        /** 展示标签，如 "城市" / "其他" */
        private String label;

        /** DB 场景枚举：URBAN | HIGHWAY | TUNNEL | TOLL_GATE | ROUNDABOUT（"其他"分组为 null） */
        private String sceneType;

        /** 切片数量 */
        private Long count;

        /** 占比（%），保留 1 位小数 */
        private Double percentage;
    }
}
