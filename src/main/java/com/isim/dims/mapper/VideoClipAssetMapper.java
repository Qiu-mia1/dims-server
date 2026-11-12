package com.isim.dims.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 视频片段（Clip）级 embedding 向量表（video_clip_asset，PostgreSQL + pgvector）Mapper
 *
 * <p>与 {@link ImageAssetMapper}（帧级）构成双路召回：
 * 帧级 HNSW 抓「某一帧像不像」，片段级 HNSW 抓「一段时间像不像」，
 * 两路候选合并成 Top-N 候选池后交给 Reranker。</p>
 *
 * <p>本表由 {@code db/pgvector/video_clip_asset.sql} 建表；未建表时调用会抛异常，
 * 由 {@code VideoSemanticSearchServiceImpl} 捕获后自动降级为仅帧级召回，
 * 并通过 {@code clipRecallAvailable} 标记避免每次请求重复报错。</p>
 *
 * <p>score = 1 - (embedding &lt;=&gt; 查询向量) 即余弦相似度，与帧级口径一致。</p>
 * @feature: visualization
 */
@Mapper
@DS("pgvector")
public interface VideoClipAssetMapper {

    /**
     * 片段级 pgvector 余弦相似度 Top-N 检索（带元数据条件下推）
     *
     * <p>切片集合 / topic / 时间窗条件下推到 SQL，减少候选池中需要
     * 在 Java 侧二次过滤的噪声；HNSW 索引负责近似最近邻排序。</p>
     *
     * @param vec      pgvector 向量字面量字符串，如 "[0.1,0.2,...]"
     * @param limit    返回条数上限
     * @param sliceIds 限定切片 ID 集合（null/空 = 不限）
     * @param topics   限定 topic_safe 集合（null/空 = 不限）
     * @param startNs  片段结束时间下界（null = 不限）
     * @param endNs    片段开始时间上界（null = 不限）
     * @return 每项含 slice_id / topic / topic_safe / start_ns / end_ns / frame_count / score
     */
    @Select("<script>"
            + "SELECT slice_id, topic, topic_safe, start_ns, end_ns, frame_count, "
            + "(1 - (embedding &lt;=&gt; #{vec}::vector)) AS score "
            + "FROM video_clip_asset "
            + "WHERE embedding IS NOT NULL "
            + "<if test=\"sliceIds != null and sliceIds.size() &gt; 0\">"
            + "  AND slice_id IN "
            + "  <foreach collection=\"sliceIds\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
            + "</if>"
            + "<if test=\"topics != null and topics.size() &gt; 0\">"
            + "  AND topic_safe IN "
            + "  <foreach collection=\"topics\" item=\"t\" open=\"(\" separator=\",\" close=\")\">#{t}</foreach>"
            + "</if>"
            + "<if test=\"startNs != null\"> AND end_ns &gt;= #{startNs}</if>"
            + "<if test=\"endNs != null\"> AND start_ns &lt;= #{endNs}</if>"
            + " ORDER BY embedding &lt;=&gt; #{vec}::vector "
            + "LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> searchByEmbedding(@Param("vec") String vec,
                                                @Param("limit") int limit,
                                                @Param("sliceIds") List<String> sliceIds,
                                                @Param("topics") List<String> topics,
                                                @Param("startNs") Long startNs,
                                                @Param("endNs") Long endNs);
}
