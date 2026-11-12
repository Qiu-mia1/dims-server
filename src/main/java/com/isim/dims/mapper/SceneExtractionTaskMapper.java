package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.SceneExtractionTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 场景提取任务 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 scene_extraction_task 表的完整 CRUD。
 * 任务串行/重试检查通过 LambdaQueryWrapper 完成（见 SceneExtractionPipelineServiceImpl）。
 * @feature: ingestion
 */
@Mapper
public interface SceneExtractionTaskMapper extends BaseMapper<SceneExtractionTask> {
}
