package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场景规则全局阈值配置表 (scene_rule_config)
 *
 * 对应 dims-python/scene_extraction/rules/scene_rules.yaml 中 thresholds 段的键值存储，
 * 供规则引擎消费的全局阈值（典型车道宽度、安全跟车距离、TTC 阈值等）。
 * 导出时以 {config_key: config_value} 映射形式写入 rules 配置。
 * @feature: ingestion
 */
@Data
@TableName("scene_rule_config")
public class SceneRuleConfig {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 阈值 key（如 lane_width_typical），唯一 */
    @TableField("config_key")
    private String configKey;

    /** 阈值数值 */
    @TableField("config_value")
    private Double configValue;

    /** 单位（m / s / m/s / rad/s 等） */
    @TableField("unit")
    private String unit;

    /** 阈值说明 */
    @TableField("description")
    private String description;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
