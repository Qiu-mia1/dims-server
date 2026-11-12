package com.isim.dims.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 用户个性化配置方案 —— 详情响应（查询 / 新建 / 编辑共用）
 *
 * <p>输出字段：{@code config_id} / {@code user_id} / {@code name} / {@code description}
 * / {@code config_json} / {@code created_at} / {@code updated_at}（全局 Jackson SNAKE_CASE）。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigDetailResponse {

    /** 方案 ID（user_dashboard_config.id） */
    private String configId;

    /** IAMS 用户 UUID */
    private String userId;

    /** 方案名称 */
    private String name;

    /** 方案描述；{@code null} 表示无描述 */
    private String description;

    /** 前端组装的配置 JSON；后端不解析内容 */
    private JsonNode configJson;

    /** 创建时间（RFC3339） */
    private String createdAt;

    /** 最近更新时间（RFC3339） */
    private String updatedAt;
}
