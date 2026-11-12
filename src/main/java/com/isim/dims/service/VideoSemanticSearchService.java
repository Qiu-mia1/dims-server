package com.isim.dims.service;

import com.isim.dims.dto.VideoSemanticSearchRequest;
import com.isim.dims.dto.VideoSemanticSearchResponse;

/**
 * 视频语义检索服务（以文搜图 / 以图搜图 升级版）
 *
 * <p>检索流程：
 * <pre>
 *   MP4 → FFmpeg → Frame(每 5s 帧图) + Clip(时间片段) → SigLIP2 → 768D → pgvector
 *       → Frame HNSW + Clip HNSW          双路召回
 *       → Top 100 候选池
 *       → Metadata Filter（切片状态/VIN/来源/topic/时间）
 *       → Temporal Score（邻帧时间连续性互证）
 *       → Reranker（加权融合）
 *       → Top 20
 *       → 输出：视频 + 时间区间 + 帧
 * </pre>
 * 与 {@link ImageSearchService}（单帧 Top-N，返回零散帧图）的区别在于：
 * 本服务把命中帧按时间聚合成「视频片段区间」，直接给出可跳转播放的结果。</p>
 *
 * <p>未启用 dims.siglip.enabled 时抛 FEATURES_DISABLED。</p>
 * @feature: visualization
 */
public interface VideoSemanticSearchService {

    /**
     * 以文搜图：用文本 prompt 检索视频片段
     *
     * @param request 检索请求（text 必填）
     * @return 命中区间列表（视频 + 时间区间 + 帧）
     */
    VideoSemanticSearchResponse searchByText(VideoSemanticSearchRequest request);

    /**
     * 以图搜图：用上传图片检索视频片段
     *
     * @param imageBytes 图片字节（JPEG/PNG）
     * @param request    检索请求（text 可为空）
     * @return 命中区间列表（视频 + 时间区间 + 帧）
     */
    VideoSemanticSearchResponse searchByImage(byte[] imageBytes, VideoSemanticSearchRequest request);
}
