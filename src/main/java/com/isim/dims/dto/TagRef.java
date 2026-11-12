package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标签引用（轻量视图）。
 *
 * <p>切片列表/详情、标签下拉只需要「id + 名称 + 分类路径」三样东西。
 * 它取代了此前直出前端的 {@code entity.Tag}（那会把 {@code rule_expr} 等内部字段一并暴露）。
 *
 * @feature: tag
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagRef {

    /** 标签 UUID（SIMS 片段域标签 ID，data_slice_tag.tag_id 即引用此值）。 */
    private String id;

    /** 标签名。 */
    private String name;

    /** 分类完整路径，如「自定义标签 / 道路 / 高速」。 */
    private String categoryPath;
}
