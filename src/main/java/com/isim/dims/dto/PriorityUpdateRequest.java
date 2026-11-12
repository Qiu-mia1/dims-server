package com.isim.dims.dto;

import lombok.Data;

/**
 * 场景规则优先级批量更新请求项
 *
 * 对应 PUT /v1/scene-rules/priority 的请求体元素：
 * 请求体为数组 [{id, priority}, ...]。
 * @feature: ingestion
 */
@Data
public class PriorityUpdateRequest {

    /** 规则 UUID */
    private String id;

    /** 优先级 rank，数值越小越优先 */
    private Integer priority;
}
