package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入任务表 (ingestion_task)
 *
 * 对应数据库中导入任务表，记录数据切片导入过程的异步任务进度。
 * 状态流转：UPLOADING -> PARSING -> TAGGING -> READY，任一步骤失败进入 ERROR。
 * 与 data_slice 为一对一关系。
 * @feature: ingestion
 */
@Data
@TableName("ingestion_task")
public class IngestionTask {

    @JsonProperty("task_id")
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    /**
     * UPLOADING|CONVERTING|PARSING|TAGGING|READY|ERROR
     */
    @TableField("status")
    private String status;

    @TableField("progress")
    private Integer progress;

    @TableField("error_msg")
    private String errorMsg;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
