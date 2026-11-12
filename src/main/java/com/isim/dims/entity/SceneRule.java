package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 场景判断规则表 (scene_rule)
 *
 * 对应 dims-python/scene_extraction 配置驱动引擎（v2）的单条规则结构化存储，
 * 导出（GET /v1/scene-rules/export）结构对齐 rules/*.yaml（如 cut_in.yaml），
 * Python 的 RuleLoader.load() 可直接加载。
 *
 * v2 字段（engine/model.py 对齐）：
 *   logic:            "all" | "any"（条件组合逻辑）
 *   confidence:       置信度阈值
 *   entity_roles:     {role_name: {selector, types}}（实体角色声明）
 *   conditions:       [{field, operator, value?, value_type?, unit?,
 *                      negate?, params?, description?}, ...]
 *   time_window:      {min_duration_s, max_duration_s, description?}
 *   output_parameters:[{name, feature, aggregate?, round?}, ...]（替代 v1 output_params）
 *   postprocess:      [{type, params?}, ...]
 *   features:         [{name, expression, unit?}, ...]（声明式复合特征）
 *   min_engine_version: "2.0" 等（版本门禁）
 *
 * conditions / time_window / output_params / entity_roles / output_parameters /
 * postprocess / features 为 MySQL JSON 列，由 JacksonTypeHandler 完成对象 ⇄ JSON
 * 双向映射（实体需 autoResultMap=true 才能在 SELECT 时生效）。
 * 单位/坐标系沿用源 YAML 声明（长度 m、速度 m/s、角度 deg/rad/s）。
 *
 * priority 为优先级 rank，数值越小越优先（对齐 v2 YAML 的 rules[].priority）。
 * 旧列 output_params（v1 字符串数组）保留不动，向后兼容 v1 消费方。
 * @feature: ingestion
 */
@Data
@TableName(value = "scene_rule", autoResultMap = true)
public class SceneRule {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 场景类型 key（lane_change 等），唯一 */
    @TableField("scene_type")
    private String sceneType;

    /** 场景描述 */
    @TableField("description")
    private String description;

    /** 参考标准/来源（如 NHTSA Pre-Crash Scenario 分类） */
    @TableField("reference")
    private String reference;

    /** 优先级 rank，数值越小越优先，默认 99 */
    @TableField("priority")
    private Integer priority;

    /** 是否启用 */
    @TableField("is_enabled")
    private Boolean isEnabled;

    /** 规则版本 */
    @TableField("version")
    private String version;

    /** 触发条件数组（元素结构见类注释） */
    @TableField(value = "conditions",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private List<Map<String, Object>> conditions;

    /** 时间窗口 {min_duration_s, max_duration_s, description?} */
    @TableField(value = "time_window",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private Map<String, Object> timeWindow;

    /** 条件组合逻辑：all(AND) 或 any(OR) */
    @TableField("logic")
    private String logic;

    /** 置信度阈值 */
    @TableField("confidence")
    private Double confidence;

    /** 实体角色声明 {role_name: {selector, types}}；key 为角色名即特征路径前缀 */
    @TableField(value = "entity_roles",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private Map<String, Object> entityRoles;

    /** v2 输出参数对象数组 [{name, feature, aggregate, round}]，替代 v1 output_params */
    @TableField(value = "output_parameters",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private List<Map<String, Object>> outputParameters;

    /** 后处理声明数组 [{type, params}] */
    @TableField(value = "postprocess",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private List<Map<String, Object>> postprocess;

    /** 声明式复合特征数组 [{name, expression, unit}] */
    @TableField(value = "features",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private List<Map<String, Object>> features;

    /** 最低引擎版本门禁（如 "2.0"） */
    @TableField("min_engine_version")
    private String minEngineVersion;

    /** v1 遗留：输出场景特征参数名数组；已由 outputParameters 替代 */
    @Deprecated
    @TableField(value = "output_params",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private List<String> outputParams;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
