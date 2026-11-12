package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.VinMd5Mapping;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * VIN-MD5 对应关系 Mapper
 * <p>
 * 继承 MyBatis-Plus BaseMapper 提供 vin_md5_mapping 表的完整 CRUD。
 * 典型查询：按 md5（唯一）反查 VIN 及车辆元数据，或按 vin（唯一）
 * 反查 bag 文件 MD5，均命中唯一索引。
 *
 * @feature: ingestion
 */
@Mapper
public interface VinMd5MappingMapper extends BaseMapper<VinMd5Mapping> {

    /**
     * 批量写入/更新 VIN-MD5 映射（PostgreSQL upsert）
     *
     * <p>等价于：</p>
     * <pre>
     * INSERT INTO vin_md5_mapping (vin, md5)
     * SELECT DISTINCT vin, md5 FROM (VALUES ('vin1','md51'), ('vin2','md52')) AS v(vin, md5)
     * ON CONFLICT (vin) DO UPDATE SET md5 = EXCLUDED.md5;
     * </pre>
     *
     * <p>按 vin 唯一约束冲突时覆盖 md5（重新导入车辆信息场景）；
     * ⚠️ 入参必须已按 vin 去重，否则 PostgreSQL 报
     * 「ON CONFLICT DO UPDATE command cannot affect row a second time」。</p>
     *
     * @param rows 待写入行（仅使用 vin / md5 两列）
     * @return 受影响行数
     */
    @Insert("<script>"
            + "INSERT INTO vin_md5_mapping (vin, md5) "
            + "SELECT DISTINCT vin, md5 FROM (VALUES "
            + "<foreach collection='list' item='item' separator=','>(#{item.vin}, #{item.md5})</foreach>"
            + ") AS v(vin, md5) "
            + "ON CONFLICT (vin) DO UPDATE SET md5 = EXCLUDED.md5"
            + "</script>")
    int upsertBatch(@Param("list") List<VinMd5Mapping> rows);
}
