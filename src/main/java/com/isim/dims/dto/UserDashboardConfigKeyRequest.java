package com.isim.dims.dto;

import lombok.Data;

/**
 * 用户个性化配置方案 —— 定位请求（详情 / 删除 / 导出 共用）
 *
 * <p>请求体：{@code {"config_id": "..."}}。
 * 三者都只需要「哪个方案」，故共用一个请求体结构。</p>
 *
 * <p><b>方案归属用户不在请求体里</b>：取值来源是 JWT 的 {@code sub} claim
 * （见 {@code UserDashboardConfigController}）。由调用方自报身份会造成水平越权——
 * 任何人都能在请求体里填别人的 userId 去读写他人方案。</p>
 *
 * <p>注意：全局 Jackson {@code property-naming-strategy: SNAKE_CASE}，
 * 故 JSON 字段名为 {@code config_id} 而非驼峰。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigKeyRequest {

    /** 方案 ID（必填） */
    private String configId;
}
