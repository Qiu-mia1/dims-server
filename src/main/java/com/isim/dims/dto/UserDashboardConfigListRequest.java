package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 用户个性化配置方案 —— 分页列表请求
 *
 * <p>请求体：{@code {"page": 1, "page_size": 20}}，两个字段都可省略。</p>
 *
 * <p><b>方案归属用户不在请求体里</b>：取值来源是 JWT 的 {@code sub} claim
 * （见 {@code UserDashboardConfigController}）。由调用方自报身份会造成水平越权——
 * 任何人都能在请求体里填别人的 userId 去读写他人方案。</p>
 *
 * <p><b>分页字段刻意声明为包装类型 {@link Integer}</b>：JSON 反序列化时字段缺失即为
 * {@code null}，Controller 据此回落默认值（page=1 / page_size=20）。
 * 若用基本类型 {@code int}，缺字段会得到 {@code 0}，
 * 与「显式传 0」无法区分——page_size 会被误钳制成 1 而不是回落 20。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigListRequest {

    /** 页码，从 1 开始；缺省或 &lt;1 一律钳制为 1 */
    private Integer page;

    /** 每页条数；缺省为 20，钳制在 [1, 100] */
    @JsonProperty("page_size")
    private Integer pageSize;
}
