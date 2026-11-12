package com.isim.dims.service;

import com.isim.dims.dto.SliceTagResponse;

import java.util.List;

/**
 * 切片打标 Service（标签**关联**管理）。
 *
 * <p><b>职责收窄（2026-09-23）</b>：标签**定义**（标签库/分类树）已全部外移到 SIMS，
 * 本地 {@code tag} 表随 V29 移除，因此本接口不再提供标签库 CRUD —— 那是
 * {@code TagController} 直连 SIMS 的代理职责。这里只剩 DIMS 自己要持久化的东西：
 * 切片与标签的多对多**关联**（{@code data_slice_tag}）。
 *
 * <p>标签名不在关联表里，由 {@link SimsTagCatalog} 解析（SIMS 标签被删时回落
 * 「未知标签(...)」占位，不阻塞列表）。
 *
 * @feature: tag
 */
public interface TagService {

    /**
     * 查询切片当前关联的标签。
     *
     * @param sliceId 切片 UUID
     * @return 标签响应列表（含 name / type / timestamp）
     */
    List<SliceTagResponse> getSliceTags(String sliceId);

    /**
     * 批量设置切片标签（覆盖式更新）
     *
     * <p>删除切片上所有已有关联，重新建立指定 tagId 的关联。
     *
     * @param sliceId 切片 UUID
     * @param tagIds  标签 UUID 列表（SIMS 片段域标签 ID）
     * @param source  标签来源（auto|manual）
     */
    void setSliceTags(String sliceId, List<String> tagIds, String source);
}
