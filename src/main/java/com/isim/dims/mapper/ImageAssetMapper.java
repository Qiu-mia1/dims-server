package com.isim.dims.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 帧图 embedding 向量表（image_asset，PostgreSQL + pgvector）Mapper
 *
 * <p>数据由 dims-python/extract_video_segment.py 在保存帧图后计算 768 维
 * embedding 写入 pgvector；此处仅做向量相似度检索（Top-N），
 * 不再像旧实现那样全库扫文件。</p>
 *
 * <p>{@code @DS("pgvector")} 将该 Mapper 切换到独立向量数据源
 * （spring.datasource.dynamic.datasource.pgvector / 顶层 pgvector 配置）。</p>
 *
 * <p>score = 1 - (embedding <=> 查询向量) 即余弦相似度，与旧实现口径一致。</p>
 * @feature: visualization
 */
@Mapper
@DS("pgvector")
public interface ImageAssetMapper {

    /**
     * pgvector 余弦相似度 Top-N 检索
     *
     * <p>使用 HNSW 向量索引加速（见 image_asset 表上
     * hnsw(embedding vector_cosine_ops) 索引）。</p>
     *
     * @param vec   pgvector 向量字面量字符串，如 "[0.1,0.2,...]"
     * @param limit 返回条数上限
     * @return 每项含 slice_id / topic / topic_safe / file_name / ts / score
     */
    @Select("SELECT slice_id, topic, topic_safe, file_name, frame_ns AS ts, "
            + "(1 - (embedding <=> #{vec}::vector)) AS score "
            + "FROM image_asset "
            + "WHERE embedding IS NOT NULL "
            + "ORDER BY embedding <=> #{vec}::vector "
            + "LIMIT #{limit}")
    List<Map<String, Object>> searchByEmbedding(@Param("vec") String vec,
                                                @Param("limit") int limit);

    /**
     * 帧级 pgvector 余弦相似度 Top-N 检索（带元数据条件下推）
     *
     * <p>在 HNSW 近似检索的同时下推 slice_id / topic_safe / 时间窗条件，
     * 使候选池（Top 100）更贴近业务约束，降低 Reranker 阶段的无效计算。</p>
     *
     * @param vec      pgvector 向量字面量字符串，如 "[0.1,0.2,...]"
     * @param limit    候选池条数上限
     * @param sliceIds 限定切片 ID 集合（null/空 = 不限）
     * @param topics   限定 topic_safe 集合（null/空 = 不限）
     * @param startNs  帧时间下界（null = 不限）
     * @param endNs    帧时间上界（null = 不限）
     * @return 每项含 slice_id / topic / topic_safe / file_name / ts / score
     */
    @Select("<script>"
            + "SELECT slice_id, topic, topic_safe, file_name, frame_ns AS ts, "
            + "(1 - (embedding &lt;=&gt; #{vec}::vector)) AS score "
            + "FROM image_asset "
            + "WHERE embedding IS NOT NULL "
            + "<if test=\"sliceIds != null and sliceIds.size() &gt; 0\">"
            + "  AND slice_id IN "
            + "  <foreach collection=\"sliceIds\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
            + "</if>"
            + "<if test=\"topics != null and topics.size() &gt; 0\">"
            + "  AND topic_safe IN "
            + "  <foreach collection=\"topics\" item=\"t\" open=\"(\" separator=\",\" close=\")\">#{t}</foreach>"
            + "</if>"
            + "<if test=\"startNs != null\"> AND frame_ns &gt;= #{startNs}</if>"
            + "<if test=\"endNs != null\"> AND frame_ns &lt;= #{endNs}</if>"
            + " ORDER BY embedding &lt;=&gt; #{vec}::vector "
            + "LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> searchByEmbeddingFiltered(@Param("vec") String vec,
                                                        @Param("limit") int limit,
                                                        @Param("sliceIds") List<String> sliceIds,
                                                        @Param("topics") List<String> topics,
                                                        @Param("startNs") Long startNs,
                                                        @Param("endNs") Long endNs);

    /**
     * 按切片删除帧图向量记录（切片删除级联清理）
     *
     * <p>命中 {@code idx_image_asset_slice(slice_id)} 索引，按切片整体清理，
     * 避免删除切片后残留孤儿 embedding 被以文搜图/以图搜图召回。</p>
     *
     * @param sliceId 切片 ID
     * @return 删除行数
     */
    @Delete("DELETE FROM image_asset WHERE slice_id = #{sliceId}")
    int deleteBySliceId(@Param("sliceId") String sliceId);

    /**
     * 按切片删除视频片段向量记录（切片删除级联清理）
     *
     * <p>video_clip_asset 与 image_asset 同在 pgvector 向量库，故复用本 Mapper 的
     * {@code @DS("pgvector")} 数据源切换，不额外引入 Mapper。</p>
     *
     * <p>该表为可选建表（未建时检索端自动降级为仅帧级召回），
     * 因此未建表导致的异常由调用方捕获降级，不影响切片删除主流程。</p>
     *
     * @param sliceId 切片 ID
     * @return 删除行数
     */
    @Delete("DELETE FROM video_clip_asset WHERE slice_id = #{sliceId}")
    int deleteClipAssetBySliceId(@Param("sliceId") String sliceId);
}