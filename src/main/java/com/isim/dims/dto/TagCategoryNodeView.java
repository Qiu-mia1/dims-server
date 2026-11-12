package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 片段域分类树节点（转发给前端的视图）。
 *
 * <p><b>⚠️ 命名策略：本类跨了两种命名约定，改动前务必看清。</b>
 * <ul>
 *   <li><b>出参</b>（DIMS → 前端）：全局 Jackson 策略是 {@code SNAKE_CASE}
 *       （{@code spring.jackson.property-naming-strategy}，对齐 OpenAPI 契约），
 *       所以 {@code parentId} 出成 {@code parent_id}、{@code nodeType} 出成 {@code node_type}、
 *       {@code sortOrder} 出成 {@code sort_order}；</li>
 *   <li><b>入参</b>（SIMS → DIMS）：SIMS 契约是 <b>camelCase</b>（{@code parentId} /
 *       {@code nodeType} / {@code isLeaf} / {@code isSystem} / {@code sortOrder}）。
 *       两者对不上，所以 {@code TagController} **手工逐字段映射**，不用 convertValue
 *       —— 否则 {@code nodeType} 会静默读成 null（曾导致内置区只读校验整体失效）。</li>
 * </ul>
 *
 * <p>⚠️ {@code is_leaf} / {@code is_system} 必须显式标注 {@link JsonProperty}：
 * Lombok 对 {@code boolean leaf} 生成的是 {@code isLeaf()} 访问器，Jackson 推导出的属性名
 * 是 {@code leaf} —— 用 {@code @JsonProperty} 定死出参键名，避免前端拿到 {@code leaf}。
 *
 * @feature: tag
 */
@Data
public class TagCategoryNodeView {

    private String id;

    private String parentId;

    private String name;

    /** 归属分区：BUILTIN（内置，DIMS 只读）/ CUSTOM（自定义，可管理）。 */
    private String nodeType;

    /** 叶子分类才可挂标签。 */
    @JsonProperty("is_leaf")
    private boolean leaf;

    /** 系统预置节点（不可重命名/删除）。 */
    @JsonProperty("is_system")
    private boolean system;

    private int sortOrder;

    private List<TagCategoryNodeView> children = new ArrayList<>();
}
