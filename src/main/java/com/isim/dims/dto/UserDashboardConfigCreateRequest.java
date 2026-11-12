package com.isim.dims.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 用户个性化配置方案 —— 新建请求
 *
 * <p>请求体：{@code {"name": "...", "description": "...", "config_json": { ... }}}。
 * 配置内容由前端组装，后端不解析其语义。</p>
 *
 * <p><b>方案归属用户不在请求体里</b>：取值来源是 JWT 的 {@code sub} claim
 * （见 {@code UserDashboardConfigController}）。由调用方自报身份会造成水平越权——
 * 任何人都能在请求体里填别人的 userId 把数据写到他人名下。</p>
 *
 * <p>注意：全局 Jackson {@code property-naming-strategy: SNAKE_CASE}，
 * 故 JSON 字段名为 {@code config_json} 而非驼峰。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigCreateRequest {

    /** 方案名称（必填，非空白，≤120 字符；单用户内唯一） */
    private String name;

    /** 方案描述（可选，≤500 字符；空白一律归一为 NULL） */
    private String description;

    /** 前端组装的配置 JSON（必填）；后端不解析，仅原样落库 */
    private JsonNode configJson;
}
