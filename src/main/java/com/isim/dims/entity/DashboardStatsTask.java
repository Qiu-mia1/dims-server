package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 驾驶舱统计提取任务表 (dashboard_stats_task)
 *
 * 记录切片驾驶舱统计提取管道的异步状态机。
 * 状态机（与 scene_extraction_task 同构）：
 *   PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试）
 * 提取成功后将指标写入 slice_stats（1:1 data_slice）。
 * @feature: dashboard
 */
@Data
@TableName("dashboard_stats_task")
public class DashboardStatsTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    /**
     * PENDING|RUNNING|SUCCESS|FAILED
     */
    @TableField("status")
    private String status;

    /** 进度 0-100 */
    @TableField("progress")
    private Integer progress;

    /** 错误信息（FAILED 时非空） */
    @TableField("error_msg")
    private String errorMsg;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 任务结束时间 UTC（SUCCESS/FAILED 时写入） */
    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
