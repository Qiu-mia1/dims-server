package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入任务状态变更历史表 (ingestion_task_status_history)
 *
 * 记录 ingestion_task 每次状态变更的时间戳，
 * 用于前端 StatusTimeline 组件渲染状态流转时间线。
 * @feature: ingestion
 */
@Data
@TableName("ingestion_task_status_history")
public class IngestionTaskStatusHistory {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("task_id")
    private String taskId;

    @TableField("status")
    private String status;

    @TableField("progress")
    private Integer progress;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
