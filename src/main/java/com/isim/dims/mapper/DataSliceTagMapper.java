package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.dto.SliceTagResponse;
import com.isim.dims.entity.DataSliceTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 切片-标签关联 Mapper
 *
 * <p>只操作 {@code data_slice_tag} 自身。原先的两处 {@code INNER JOIN tag} 已移除：
 * 本地 {@code tag} 表随 V29 删除，标签名由 {@code SimsTagCatalog} 从 SIMS 词表解析。
 *
 * <p>⚠️ 副产品：{@code INNER JOIN} 被去掉后，**指向已失效标签的关联行不再被静默过滤掉**。
 * 这是有意的 —— 关联行是「当时确实打过这个标」的事实记录，不该因为标签定义被删就消失；
 * 名称侧给「未知标签(...)」占位提示即可。
 *
 * @feature: tag
 */
@Mapper
public interface DataSliceTagMapper extends BaseMapper<DataSliceTag> {

    /**
     * 查询指定切片的打标关联行（不联表，标签名由调用方补）。
     *
     * @param sliceId 切片 ID
     * @return 关联行列表
     */
    @Select("SELECT id, slice_id, tag_id, source, created_at " +
            "FROM data_slice_tag WHERE slice_id = #{sliceId} ORDER BY created_at, id")
    List<DataSliceTag> selectBySliceId(@Param("sliceId") String sliceId);

    /**
     * 查询指定切片的标签响应（含 type/source 与 timestamp）。
     *
     * <p>{@code timestamp} 取关联行自身的 {@code created_at}（打标时刻）。
     * V29 之前取的是 {@code tag.created_at}（标签**定义**的创建时间），语义错误；
     * tag 表移除后已修正为打标时间。
     *
     * <p>{@code name} 不在此 SQL 内解析，由 Service 从词表缓存补。
     *
     * @param sliceId 切片 ID
     * @return 标签响应列表（name 为 null，调用方填充）
     */
    @Select("SELECT tag_id AS id, source AS type, " +
            "to_char(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS timestamp " +
            "FROM data_slice_tag WHERE slice_id = #{sliceId} ORDER BY created_at, id")
    List<SliceTagResponse> selectSliceTagResponseBySliceId(@Param("sliceId") String sliceId);
}
