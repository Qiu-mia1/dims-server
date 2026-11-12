package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 切片场景列表响应（分页）
 *
 * 对应 OpenAPI schema: SceneListResponse。
 * required: [items, total, page, page_size]
 *
 * 与 SliceListResponse / ExportListResponse 分页结构一致。
 * 列表项为轻量视图（DetectedSceneDTO 全字段），详情接口
 * GET /v1/scenes/{sceneId} 返回同一结构（无额外展开字段）。
 *
 * @feature: ingestion
 */
@Data
public class SceneListResponse {

    /** 场景列表，按 start_time 升序 */
    private List<DetectedSceneDTO> items;

    /** 命中总数（过滤后） */
    private Long total;

    /** 页码，默认 1 */
    private Integer page;

    /** 每页条数，默认 50，最大 500 */
    private Integer pageSize;
}
