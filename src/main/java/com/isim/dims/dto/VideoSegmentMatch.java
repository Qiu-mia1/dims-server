package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 视频语义检索命中结果：视频 + 时间区间 + 帧
 *
 * <p>一次命中即一个「可播放的视频片段区间」，包含：
 * <ul>
 *   <li>视频归属：slice_id / slice_name / vin / topic（摄像头）</li>
 *   <li>时间区间：start_ns ~ end_ns（bag 纳秒时间轴，与 index.json 的 timestamp 同源）</li>
 *   <li>关键帧：区间内相似度最高的若干帧及其访问地址</li>
 *   <li>打分明细：Reranker 各分量，便于前端排序解释与阈值调优</li>
 * </ul>
 * @feature: visualization
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoSegmentMatch {

    // ========== 视频归属 ==========

    /** 所属数据切片 ID */
    private String sliceId;

    /** 数据切片名称 */
    private String sliceName;

    /** 切片状态（如 READY） */
    private String sliceStatus;

    /** 车辆 VIN */
    private String vin;

    /** 数据来源类型（MMT / ZYT / Hirain） */
    private String sourceType;

    /** 事件名称（若有） */
    private String eventName;

    /** 原始摄像头 topic */
    private String topic;

    /** 规范化后的 topic（用于拼接 URL） */
    private String topicSafe;

    /** 视频播放地址（流式预览） */
    private String videoUrl;

    // ========== 时间区间 ==========

    /** 区间起始 bag 纳秒时间戳（含） */
    private Long startNs;

    /** 区间结束 bag 纳秒时间戳（含） */
    private Long endNs;

    /** 区间起始时间（ISO-8601 UTC；时间戳非 Unix 纳秒量级时为 null） */
    private String startTime;

    /** 区间结束时间（ISO-8601 UTC；同上） */
    private String endTime;

    /** 区间时长（秒） */
    private Double durationSec;

    // ========== 打分明细 ==========

    /** Reranker 综合得分（0~1，越大越相关） */
    private Double score;

    /** 区间内最高帧余弦相似度 */
    private Double frameScore;

    /** 区间内 Top3 帧相似度均值 */
    private Double meanScore;

    /** 时间连续性得分（邻帧互证程度，0~1） */
    private Double temporalScore;

    /** 片段级（Clip HNSW）相似度，未启用或無重叠时为 0 */
    private Double clipScore;

    /** 帧覆盖度（0~1） */
    private Double coverageScore;

    /** 区间内命中帧数量 */
    private Integer frameCount;

    // ========== 关键帧 ==========

    /** 区间内得分最高的若干帧（默认 3 张） */
    private List<FrameHit> frames;

    /**
     * 区间内的一张命中帧
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrameHit {

        /** 帧 bag 纳秒时间戳 */
        private Long ts;

        /** 帧时间（ISO-8601 UTC；时间戳非 Unix 纳秒量级时为 null） */
        private String tsIso;

        /** 帧图文件名（如 1234567.jpg） */
        private String fileName;

        /** 帧图访问地址：/v1/data-slices/{sliceId}/video-images/{topicSafe}/{fileName} */
        private String url;

        /** 该帧与查询的余弦相似度 */
        private Double score;
    }
}
