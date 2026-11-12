package com.isim.dims.dto;

import lombok.Data;

/**
 * 用户个性化配置方案 —— 列表摘要项
 *
 * <p><b>刻意不含 {@code config_json}</b>：列表接口只回摘要，前端选定方案后再调详情接口
 * 取配置本体，避免单用户方案数多、配置体积大时响应体膨胀。</p>
 *
 * <p>{@code description} 属于短元数据（≤500 字符）而非 payload，故保留在摘要中，
 * 支持列表直接展示方案描述。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigSummaryResponse {

    /** 方案 ID（user_dashboard_config.id） */
    private String configId;

    /** IAMS 用户 UUID */
    private String userId;

    /** 方案名称 */
    private String name;

    /** 方案描述；{@code null} 表示无描述 */
    private String description;

    /** 创建时间（RFC3339） */
    private String createdAt;

    /** 最近更新时间（RFC3339） */
    private String updatedAt;
}
