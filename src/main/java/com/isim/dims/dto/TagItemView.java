package com.isim.dims.dto;

import lombok.Data;

/**
 * 标签详情视图（转发给前端的 SIMS TagItem 子集）。
 *
 * <p>只透出 DIMS 标签管理页实际用到的字段。刻意**不透出** {@code rule} /
 * {@code ruleConditionCount} / {@code hasRule}：片段域标签的 rule 恒为 null
 * （SIMS 服务层校验），透出只会误导。
 *
 * <p>{@code createdAt} 保持 SIMS 原样字符串（ISO-8601 UTC），与 DIMS 既有
 * 时间字段约定一致（见 {@code SliceListResponse.startTs}），由前端按需格式化。
 *
 * @feature: tag
 */
@Data
public class TagItemView {

    private String id;

    private String categoryId;

    /** 分类完整路径，如「自定义标签 / 道路 / 高速」。 */
    private String categoryPath;

    private String name;

    private String description;

    private String createdByName;

    private String createdAt;
}
