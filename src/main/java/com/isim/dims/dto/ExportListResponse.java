package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 全局导出任务列表响应（分页）
 *
 * 对应 GET /v1/exports 的响应，按 created_at DESC 排序。
 * OpenAPI schema: ExportListResponse
 * @feature: export
 */
@Data
public class ExportListResponse {

    /** 导出任务摘要列表 */
    private List<ExportTaskSummary> items;

    /** 匹配条件的总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private int page;

    /** 每页条数 */
    @JsonProperty("page_size")
    private int pageSize;
}
