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
 * 导出任务表 (export_task)
 *
 * 对应数据库中导出任务表，记录按时间窗口和 Topic 列表裁剪导出 bag 文件的任务。
 * 状态流转：PENDING -> PROCESSING -> READY，或 PROCESSING -> ERROR。
 * EXPIRED 状态表示下载链接已过期（默认 created_at + 24h）。
 * topics 在 DB 中为 JSON 数组字符串，Entity 层声明为 String。
 * @feature: export
 */
@Data
@TableName("export_task")
public class ExportTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    @TableField("start_ts")
    private LocalDateTime startTs;

    @TableField("end_ts")
    private LocalDateTime endTs;

    /**
     * JSON 数组字符串，导出的 Topic 列表，空表示导出全部
     */
    @TableField(value = "topics", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String topics;

    /**
     * bag|mp4|images|json
     */
    @TableField("format")
    private String format;

    @TableField("file_path")
    private String filePath;

    @TableField("file_size")
    private Long fileSize;

    /**
     * PENDING|PROCESSING|READY|ERROR|EXPIRED
     */
    @TableField("status")
    private String status;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 最近更新时间（V9 迁移新增列），PENDING → PROCESSING → READY/ERROR 流转时更新 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
