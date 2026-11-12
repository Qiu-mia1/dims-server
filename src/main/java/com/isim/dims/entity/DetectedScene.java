package com.isim.dims.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 已检测场景表 (detected_scene)
 *
 * 场景提取任务成功后将 detected_scenes.json 中的结果逐条持久化。
 * 字段与 dims-python/scene_extraction/engine/model.py DetectedScene（L3 输出）
 * 逐字段对齐；external_scene_id 保留引擎段原始 scene_id（段序号），
 * 本表主键 id 为 DB 生成的 UUID（API 对外使用，替代段序号）。
 *
 * 时间约定：start_time / end_time 为 bag 时间轴上的 UTC 时间，
 * DATETIME(3) 毫秒精度；duration_s = end - start（秒）。
 * parameters 为规则 output_parameters 聚合结果（unit 由规则声明）。
 * rule_id 通过 scene_type 匹配 scene_rule 表（启用规则）；内置规则兜底时为 null。
 * @feature: ingestion
 */
@Data
@TableName(value = "detected_scene", autoResultMap = true)
public class DetectedScene {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属场景提取任务 UUID */
    @TableField("task_id")
    private String taskId;

    /** 所属切片 UUID */
    @TableField("slice_id")
    private String sliceId;

    /** 引擎输出段原始 scene_id（段序号），可空 */
    @TableField("external_scene_id")
    private String externalSceneId;

    /** 场景类型：lane_change | car_following | cut_in | overtake | free_flow（可扩展） */
    @TableField("scene_type")
    private String sceneType;

    /** 场景起始时间 UTC（bag 时间轴，毫秒精度） */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 场景结束时间 UTC（bag 时间轴，毫秒精度） */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 持续时长（秒）= end - start */
    @TableField("duration_s")
    private Double durationS;

    /** 置信度 0-1 */
    @TableField("confidence")
    private Double confidence;

    /** 规则 output_parameters 聚合结果（自由结构，unit 由规则声明） */
    @TableField(value = "parameters",
            typeHandler = SensorStream.JacksonJsonbTypeHandler.class)
    private Map<String, Object> parameters;

    /** 命中的 scene_rule 记录 UUID（可空=内置规则/规则已删除） */
    @TableField("rule_id")
    private String ruleId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
