package com.isim.dims.entity;

import org.apache.ibatis.type.JdbcType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回放提取任务表 (replay_extraction_task)
 *
 * 记录障碍物/Raw 消息按需提取任务的异步状态机与产物路径。
 * 状态机（与通用 TaskStatus 语义不同，勿混用）：
 *   PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次）
 *
 * topics 在 DB 中为 JSON 数组字符串，Entity 层声明为 String（与 ExportTask 一致）。
 * start_ts/end_ts 为 bag 时间戳（epoch），DATETIME(3) 毫秒精度。
 * @feature: replay
 */
@Data
@TableName("replay_extraction_task")
public class ReplayExtractionTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    /**
     * obstacles | messages | perception | map
     */
    @TableField("type")
    private String type;

    /**
     * JSON 数组字符串，目标 topic 列表
     */
    @TableField(value = "topics", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String topics;

    @TableField("start_ts")
    private LocalDateTime startTs;

    @TableField("end_ts")
    private LocalDateTime endTs;

    /**
     * PENDING|RUNNING|SUCCESS|FAILED
     */
    @TableField("status")
    private String status;

    /** 进度 0-100 */
    @TableField("progress")
    private Integer progress;

    /** 产物根目录（replay 缓存目录下） */
    @TableField("output_path")
    private String outputPath;

    /** 错误信息/摘要 */
    @TableField("message")
    private String message;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 任务结束时间（SUCCESS/FAILED 时写入） */
    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
