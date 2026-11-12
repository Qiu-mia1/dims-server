package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.SliceStats;
import org.apache.ibatis.annotations.Mapper;

/**
 * 切片统计指标 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 slice_stats 表的完整 CRUD。
 * 与 data_slice 1:1（slice_id UNIQUE），提取成功后按 slice_id upsert
 * （见 CockpitStatsPipelineServiceImpl）。
 * @feature: dashboard
 */
@Mapper
public interface SliceStatsMapper extends BaseMapper<SliceStats> {
}
