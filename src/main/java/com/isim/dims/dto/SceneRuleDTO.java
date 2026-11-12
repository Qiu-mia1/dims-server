package com.isim.dims.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 场景规则响应 DTO
 *
 * 对应 GET /v1/scene-rules 及单条查询/创建/更新的响应体。
 * v2 契约对齐 dims-python/scene_extraction 配置驱动引擎（engine/model.py + rules/*.yaml）：
 *
 *   - conditions 元素支持 value_type（literal | range | feature_ref | expression）、
 *     params（算子参数 map，如 {window_s: 2.0}）、negate、unit
 *   - output_parameters（复数，与 v2 引擎对齐）替代 v1 的 output_params
 *   - entity_roles 的 key 为角色名（front_vehicle / target_vehicle），
 *     value 为 {selector, types}；角色名即特征路径前缀（front_vehicle.distance）
 *   - time_window 包含 min_duration_s / max_duration_s
 *   - logic 缺省 "all"，confidence 缺省 0.85，priority 缺省 99
 *
 * @feature: ingestion
 */
@Data
public class SceneRuleDTO {

    /** 规则 UUID */
    private String id;

    /** 场景类型 key（lane_change 等），唯一 */
    private String sceneType;

    /** 场景描述 */
    private String description;

    /** 参考标准/来源 */
    private String reference;

    /** 优先级 rank，数值越小越优先；缺省 99 */
    private Integer priority;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 规则版本 */
    private String version;

    /** 条件组合逻辑：all | any，缺省 "all" */
    private String logic;

    /** 置信度阈值，缺省 0.85 */
    private Double confidence;

    /** 实体角色声明；key 为角色名，value 为 {selector, types} */
    private Map<String, EntityRoleDTO> entityRoles;

    /** 触发条件数组（元素结构见类注释） */
    private List<Map<String, Object>> conditions;

    /** 时间窗口 {min_duration_s, max_duration_s, description?} */
    private Map<String, Object> timeWindow;

    /** v2 输出参数（对齐 model.py OutputParam）；替代 v1 的 outputParams */
    private List<OutputParamDTO> outputParameters;

    /** v1 遗留：输出场景特征参数名数组；已由 outputParameters 替代，待 Service 迁移后移除 */
    @Deprecated
    private List<String> outputParams;

    /** 后处理声明（merge_overlapping / trim_to_maneuver ...） */
    private List<PostProcessSpecDTO> postprocess;

    /** 声明式复合特征（v2 YAML features 段，可选） */
    private List<FeatureSpecDTO> features;

    /** 最低引擎版本（v2 YAML min_engine_version，如 "2.0"） */
    private String minEngineVersion;

    /** 创建时间（UTC） */
    private LocalDateTime createdAt;

    /** 最近更新时间（UTC） */
    private LocalDateTime updatedAt;

    /**
     * 实体角色声明（对齐 model.py EntityRole）
     *
     * 选择器负责从候选池绑定对象，types 为对象类型过滤；
     * 角色名即特征路径前缀（target_vehicle.distance → 角色 target_vehicle）。
     */
    @Data
    public static class EntityRoleDTO {

        /** 空间选择器：nearest_in_front / nearest_in_lane / nearest_side_in_front */
        private String selector;

        /** 对象类型过滤，如 ["vehicle"] */
        private List<String> types;
    }

    /**
     * 输出参数（对齐 model.py OutputParam）：特征路径 + 段上聚合方式
     */
    @Data
    public static class OutputParamDTO {

        /** 输出参数名（如 min_distance） */
        private String name;

        /** 特征路径（含实体前缀，如 target_vehicle.distance） */
        private String feature;

        /** 聚合方式：last | mean | max | min | first | max_abs，缺省 "last" */
        private String aggregate;

        /** 输出舍入位数（null = 不舍入） */
        private Integer round;
    }

    /**
     * 后处理声明（对齐 model.py PostProcessSpec）
     */
    @Data
    public static class PostProcessSpecDTO {

        /** 后处理类型：merge_overlapping / trim_to_maneuver ... */
        private String type;

        /** 后处理参数，如 {tolerance_s: 2.0} */
        private Map<String, Object> params;
    }

    /**
     * 声明式复合特征（v2 YAML features 段，可选）
     */
    @Data
    public static class FeatureSpecDTO {

        /** 特征名（供 conditions / output_parameters 引用） */
        private String name;

        /** 白名单表达式，如 "a - b" */
        private String expression;

        /** 特征单位（m / m/s / deg ...） */
        private String unit;
    }
}
