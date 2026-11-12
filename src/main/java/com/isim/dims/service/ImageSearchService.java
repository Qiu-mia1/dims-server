package com.isim.dims.service;

import java.util.List;
import java.util.Map;

/**
 * 帧图语义检索服务（以文搜图 / 以图搜图 / 特征搜图）
 *
 * <p>基于 SigLIP2 embedding，在全库每 5 秒帧图中按余弦相似度召回最相近的帧图。<br>
 * 未启用 dims.siglip.enabled 时抛 FEATURES_DISABLED。</p>
 * @feature: visualization
 */
public interface ImageSearchService {

    /**
     * 以文搜图：用文本 prompt 检索最匹配的帧图
     *
     * @param text  检索文本
     * @param limit 返回条数上限（默认 50）
     */
    List<Map<String, Object>> searchByText(String text, Integer limit);

    /**
     * 以图搜图：用上传图片检索最相似的帧图
     *
     * @param imageBytes 图片字节（JPEG/PNG）
     * @param limit      返回条数上限（默认 50）
     */
    List<Map<String, Object>> searchByImage(byte[] imageBytes, Integer limit);

    /**
     * 特征搜图：直接用 embedding 特征向量检索
     *
     * @param feature 特征向量（dims.siglip.embed-dims 维）
     * @param limit   返回条数上限（默认 50）
     */
    List<Map<String, Object>> searchByFeature(double[] feature, Integer limit);
}