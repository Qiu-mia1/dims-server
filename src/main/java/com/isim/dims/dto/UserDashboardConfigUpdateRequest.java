package com.isim.dims.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 用户个性化配置方案 —— 编辑请求（<b>全量替换语义</b>）
 *
 * <p>请求体：{@code {"config_id": "...", "name": "...",
 * "description": "...", "config_json": { ... }}}。</p>
 *
 * <p><b>全量替换</b>：{@code name} 与 {@code config_json} 必填，
 * {@code description} 可空——传 {@code null} / 省略表示<b>清空</b>描述。
 * （若采用「省略即保留」语义，描述字段将永远无法清空。）</p>
 *
 * <p><b>方案归属用户不在请求体里</b>：取值来源是 JWT 的 {@code sub} claim
 * （见 {@code UserDashboardConfigController}）。由调用方自报身份会造成水平越权——
 * 任何人都能在请求体里填别人的 userId 去改他人方案。</p>
 *
 * <p>注意：全局 Jackson {@code property-naming-strategy: SNAKE_CASE}，
 * 故 JSON 字段名为 {@code config_id} / {@code config_json} 而非驼峰。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigUpdateRequest {

    /** 待编辑的方案 ID（必填） */
    private String configId;

    /** 新方案名称（必填，非空白，≤120 字符；单用户内唯一） */
    private String name;

    /** 新方案描述（可选，≤500 字符；null 表示清空） */
    private String description;

    /** 新的配置 JSON（必填）；后端不解析，仅原样落库 */
    private JsonNode configJson;
}
