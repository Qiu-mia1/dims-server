package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场景提取任务表 (scene_extraction_task)
 *
 * 记录切片全量场景提取管道的异步状态机与产物路径。
 * 状态机（与通用 TaskStatus 语义不同，勿混用）：
 *   PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次）
 *
 * intermediate_paths 在 DB 中为 JSON 数组（管道各步产物绝对路径），
 * Entity 层声明为 List&lt;String&gt;，经 JacksonTypeHandler 双向映射
 * （autoResultMap=true 才能让 typeHandler 在 SELECT 时生效）。
 * output_path 指向引擎主产物 detected_scenes.json。
 * @feature: ingestion
 */
@Data
@TableName(value = "scene_extraction_task", autoResultMap = true)
public class SceneExtractionTask {

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

    /** 检测到的场景总数（SUCCESS 时有值） */
    @TableField("total_scenes")
    private Integer totalScenes;

    /** 主产物 detected_scenes.json 绝对路径（SUCCESS 时有值） */
    @TableField("output_path")
    private String outputPath;

    /**
     * 管道各步产物绝对路径数组：
     * [ego_trajectory_raw.json, perception/, scene_data.json, detected_scenes.json]
     */
    @TableField(value = "intermediate_paths",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private List<String> intermediatePaths;

    /** 规则格式版本（v2 配置驱动引擎 = 2） */
    @TableField("rule_version")
    private Integer ruleVersion;

    /** 最低引擎版本门禁（启用规则 max min_engine_version，如 "2.0"） */
    @TableField("engine_version")
    private String engineVersion;

    /** 错误信息（FAILED 时非空） */
    @TableField("error_msg")
    private String errorMsg;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 任务结束时间（SUCCESS/FAILED 时写入） */
    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
