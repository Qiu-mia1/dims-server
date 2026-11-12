package com.isim.dims.dto;

import lombok.Data;

/**
 * 场景规则阈值配置更新请求
 *
 * 对应 PUT /v1/scene-rules/config/{key} 的请求体。
 * config_value 必填，unit / description 可选（仅更新非空字段）。
 * @feature: ingestion
 */
@Data
public class SceneRuleConfigUpdateRequest {

    /** 阈值数值 */
    private Double configValue;

    /** 单位（m / s / m/s / rad/s 等） */
    private String unit;

    /** 阈值说明 */
    private String description;
}
