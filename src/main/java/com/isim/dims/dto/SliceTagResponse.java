package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 切片标签响应
 *
 * 对应 GET /v1/data-slices/{sliceId}/tags 的响应元素。
 * 契约格式：{ "id", "name", "type", "timestamp" }
 * - type 来自 data_slice_tag.source（auto | manual）
 * - timestamp 来自 data_slice_tag.created_at（UTC RFC3339）
 * @feature: slice
 */
@Data
public class SliceTagResponse {

    /** 标签 UUID（tag.id） */
    private String id;

    /** 标签名（tag.name） */
    private String name;

    /** 标签来源：auto（自动打标）| manual（手动关联），对应 data_slice_tag.source */
    @JsonProperty("type")
    private String type;

    /** 关联创建时间，UTC RFC3339，对应 data_slice_tag.created_at */
    @JsonProperty("timestamp")
    private String timestamp;
}
