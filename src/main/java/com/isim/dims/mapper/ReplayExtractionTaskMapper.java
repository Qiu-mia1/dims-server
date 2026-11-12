package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.ReplayExtractionTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回放提取任务 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 replay_extraction_task 表的完整 CRUD。
 * 任务去重/串行查询通过 LambdaQueryWrapper 完成（见 ReplayExtractionTaskServiceImpl）。
 * @feature: replay
 */
@Mapper
public interface ReplayExtractionTaskMapper extends BaseMapper<ReplayExtractionTask> {
}
