package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 视频语义检索响应（以文搜图 / 以图搜图）
 *
 * <p>除命中区间外，一并返回各阶段规模，便于排查召回/过滤/重排效果：
 * frame_candidate_count / clip_candidate_count → segment_count → result_count。</p>
 * @feature: visualization
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoSemanticSearchResponse {

    /** 查询类型：TEXT（以文搜图） / IMAGE（以图搜图） */
    private String queryType;

    /** 最终返回的区间数 */
    private Integer resultCount;

    /** 实际生效的结果条数上限 */
    private Integer resultLimit;

    /** 帧级 HNSW 召回条数 */
    private Integer frameCandidateCount;

    /** 片段级 HNSW 召回条数（未启用为 0） */
    private Integer clipCandidateCount;

    /** 元数据过滤后剩下的帧候选条数 */
    private Integer filteredCandidateCount;

    /** 参与 Reranker 排序的时间区间数（去重前） */
    private Integer segmentCount;

    /** 去重后命中的不同切片数（未去重时等于 result_count） */
    private Integer sliceCount;

    /** 是否按 slice_id 去重（每个切片只保留得分最高的区间） */
    private Boolean dedupBySlice;

    /** 片段级召回是否生效 */
    private Boolean clipRecallEnabled;

    /** 检索耗时（毫秒） */
    private Long costMs;

    /** 命中区间列表（视频 + 时间区间 + 帧），按 Reranker 得分降序 */
    private List<VideoSegmentMatch> segments;
}
