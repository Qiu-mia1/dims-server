package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.ExportTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导出任务 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 export_task 表的完整 CRUD。
 * @feature: export
 */
@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {
}
