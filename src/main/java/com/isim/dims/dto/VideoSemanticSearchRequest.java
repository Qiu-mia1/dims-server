package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 视频语义检索请求（以文搜图 / 以图搜图 升级版）
 *
 * <p>检索流程：
 * <pre>
 *   MP4 → FFmpeg → Frame(帧) + Clip(片段) → SigLIP2 → 768D → pgvector
 *       → Frame HNSW + Clip HNSW → Top 100 候选池
 *       → 元数据过滤 + 时间连续性打分（Temporal Score）
 *       → Reranker 加权融合 → Top 20
 *       → 输出：视频 + 时间区间 + 帧
 * </pre>
 * 结果不再是一堆零散帧图，而是聚合后的「视频片段区间」，可直接跳转播放。</p>
 *
 * <p>除 text 外所有字段均可缺省；缺省时走全库语义召回。</p>
 * @feature: visualization
 */
@Data
public class VideoSemanticSearchRequest {

    /** 检索文本（以文搜图时必填），如 "A pedestrian crossing the road" */
    private String text;

    // ========== 召回 / 结果规模 ==========

    /** 最终返回条数（Reranker 之后的 Top N），默认 20，上限 100 */
    private Integer limit;

    /** 候选池大小（两路 HNSW 召回后进入 Reranker 的规模），默认 100，上限 500 */
    private Integer candidateLimit;

    /** 是否启用片段级（Clip HNSW）召回，默认 true；表不存在时自动降级 */
    private Boolean includeClip;

    // ========== 元数据过滤（作用于候选池，等价于 SQL 侧下推 + Java 侧复核） ==========

    /** 限定切片 ID 集合 */
    private List<String> sliceIds;

    /** 限定摄像头 topic（如 /sensor/camera/front_far/video）或 topic_safe */
    private List<String> topics;

    /** 限定切片状态集合（如 READY） */
    private List<String> statuses;

    /** 限定数据来源（如 MMT / ZYT / Hirain） */
    private List<String> sourceTypes;

    /** 限定车辆 VIN 集合 */
    private List<String> vins;

    /** 切片时间下界（ISO-8601，按 trigger_time/start_ts 过滤） */
    private String timeFrom;

    /** 切片时间上界（ISO-8601） */
    private String timeTo;

    /** 帧 bag 纳秒时间戳下界 */
    private Long startNs;

    /** 帧 bag 纳秒时间戳上界 */
    private Long endNs;

    // ========== 时间区间聚合 ==========

    /** 相邻帧合并为同一时间区间的最大间隔（秒），默认 10（帧间隔约 5s，即允许跳 1 帧） */
    private Integer segmentGapSec;

    /** 单个时间区间最大跨度（秒），默认 120，超出则切分 */
    private Integer segmentMaxSpanSec;

    /** 相似度下限（余弦），低于该值的帧不参与区间聚合 */
    private Double minScore;

    /**
     * Reranker 之后是否按 slice_id 去重：每个切片只保留得分最高的区间。
     * 缺省时取服务端配置 dims.siglip.search-dedup-by-slice（默认 true）。
     *
     * <p>一个切片有 2~3 路摄像头，不去重时 Top N 会被强匹配切片重复占用名额；
     * 开启后 Top N 即 N 个不同切片。若需要「同一切片在不同摄像头上的命中分布」，
     * 传 false 关闭。</p>
     */
    private Boolean dedupBySlice;

    // ========== Reranker 权重 ==========

    /** 区间内最高帧相似度权重，默认 0.40 */
    private Double weightFrame;

    /** 区间内 Top3 帧相似度均值权重，默认 0.20 */
    private Double weightMean;

    /** 时间连续性得分权重，默认 0.15 */
    private Double weightTemporal;

    /** 片段级（Clip）相似度权重，默认 0.15 */
    private Double weightClip;

    /** 帧覆盖度权重（连续帧越多越可信），默认 0.10 */
    private Double weightCoverage;
}
