package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.IngestionTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导入任务 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 ingestion_task 表的完整 CRUD。
 * @feature: ingestion
 */
@Mapper
public interface IngestionTaskMapper extends BaseMapper<IngestionTask> {
}
