package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 功能表现 × 场景交叉分析响应（横向柱状图）
 *
 * 对应 GET /v1/dashboard/func-perf 的响应。
 * 三个功能（APA成功率 / NOA接管率 / AEB介入率），每个功能按 road_scene
 * 分组聚合；缺失场景补 rate=0、count=0，保证图表 X 轴稳定。
 * @feature: dashboard
 */
@Data
public class FuncPerfResponse {

    /** 功能表现列表 */
    private List<FunctionItem> functions;

    /**
     * 功能表现项
     */
    @Data
    public static class FunctionItem {

        /** 展示名称，如 "APA成功率" */
        private String name;

        /** 指标键，如 apa_success_rate */
        private String key;

        /** 按场景分组的表现（固定顺序：URBAN→HIGHWAY→TUNNEL→TOLL_GATE→ROUNDABOUT→其他） */
        private List<SceneRate> byScene;
    }

    /**
     * 单个场景的表现
     */
    @Data
    public static class SceneRate {

        /** DB 场景枚举（"其他"分组为 null） */
        private String sceneType;

        /** 展示标签，如 "城市" */
        private String sceneLabel;

        /** 指标值（%），无数据时为 0 */
        private double rate;

        /** 参与统计的切片数 */
        private Long count;
    }
}
