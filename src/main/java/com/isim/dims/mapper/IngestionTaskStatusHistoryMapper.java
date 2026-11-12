package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.IngestionTaskStatusHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 导入任务状态变更历史 Mapper
 *
 * 提供 ingestion_task_status_history 表的完整 CRUD，
 * 以及按 task_id 查询状态历史的方法。
 * @feature: ingestion
 */
@Mapper
public interface IngestionTaskStatusHistoryMapper extends BaseMapper<IngestionTaskStatusHistory> {

    /**
     * 按任务 ID 查询状态变更历史（按创建时间升序）
     *
     * @param taskId 任务 UUID
     * @return 状态历史列表（按时间升序）
     */
    @Select("SELECT id, task_id, status, progress, created_at " +
            "FROM ingestion_task_status_history " +
            "WHERE task_id = #{taskId} " +
            "ORDER BY created_at ASC")
    List<IngestionTaskStatusHistory> selectByTaskId(@Param("taskId") String taskId);
}
