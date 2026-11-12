package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 用户个性化配置方案列表响应（分页）
 *
 * <p>对应 {@code GET /v1/user-dashboard-configs/{userId}}，按 {@code created_at DESC} 排序。
 * 结构与 {@link ExportListResponse} / {@link RecentUploadsResponse} / {@link SceneListResponse}
 * 保持一致（{@code items} / {@code total} / {@code page} / {@code page_size}）。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigListResponse {

    /** 方案摘要列表（不含 config_json） */
    private List<UserDashboardConfigSummaryResponse> items;

    /** 匹配条件的总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private int page;

    /** 每页条数 */
    @JsonProperty("page_size")
    private int pageSize;
}
