package com.isim.dims.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 场景规则更新请求
 *
 * 对应 PUT /v1/scene-rules/{id} 的请求体。
 * 全部字段可选，仅更新非空字段（部分更新语义）。
 * v2 契约对齐 dims-python/scene_extraction 配置驱动引擎（engine/model.py + rules/*.yaml）：
 * 字段结构与 SceneRuleDTO 一致，新增/变更字段说明见 SceneRuleDTO 类注释。
 * @feature: ingestion
 */
@Data
public class SceneRuleUpdateRequest {

    /** 场景类型 key，唯一 */
    private String sceneType;

    /** 场景描述 */
    private String description;

    /** 参考标准/来源 */
    private String reference;

    /** 优先级 rank，数值越小越优先 */
    private Integer priority;

    /** 是否启用 */
    private Boolean isEnabled;

    /** 规则版本 */
    private String version;

    /** 条件组合逻辑：all | any */
    private String logic;

    /** 置信度阈值 */
    private Double confidence;

    /** 实体角色声明；key 为角色名，value 为 {selector, types} */
    private Map<String, SceneRuleDTO.EntityRoleDTO> entityRoles;

    /** 触发条件数组（元素结构见 SceneRuleDTO 类注释） */
    private List<Map<String, Object>> conditions;

    /** 时间窗口 {min_duration_s, max_duration_s, description?} */
    private Map<String, Object> timeWindow;

    /** v2 输出参数（对齐 model.py OutputParam）；替代 v1 的 outputParams */
    private List<SceneRuleDTO.OutputParamDTO> outputParameters;

    /** v1 遗留：输出场景特征参数名数组；已由 outputParameters 替代，待 Service 迁移后移除 */
    @Deprecated
    private List<String> outputParams;

    /** 后处理声明（merge_overlapping / trim_to_maneuver ...） */
    private List<SceneRuleDTO.PostProcessSpecDTO> postprocess;

    /** 声明式复合特征（v2 YAML features 段，可选） */
    private List<SceneRuleDTO.FeatureSpecDTO> features;

    /** 最低引擎版本（v2 YAML min_engine_version，如 "2.0"） */
    private String minEngineVersion;
}
