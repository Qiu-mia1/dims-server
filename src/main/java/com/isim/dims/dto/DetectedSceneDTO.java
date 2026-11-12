package com.isim.dims.dto;

import lombok.Data;

import java.util.Map;

/**
 * 已检测场景（场景提取结果持久化后的 API 视图）
 *
 * 对应 OpenAPI schema: DetectedScene。
 * required: [scene_id, slice_id, scene_type, start_time, end_time, duration_s, confidence]
 *
 * 字段与 dims-python/scene_extraction/engine/model.py DetectedScene（L3 输出）逐字段对齐：
 *   scene_type / start_time / end_time / duration_s / parameters / confidence
 * 持久化时补充：scene_id（DB 生成的 UUID，替代引擎段序号）、slice_id、
 * rule_id（命中的 scene_rule 记录，规则版本追溯）、created_at。
 *
 * 时间约定：
 *   - start_time / end_time 为 bag 时间轴上的 UTC RFC3339（unit: s）
 *   - duration_s = end_time - start_time 的秒数（unit: s）
 *
 * 单位约定（跨边界，宪法第 7 节）：
 *   - parameters 为自由结构（如 {lateral_distance, avg_speed, direction}），
 *     每个参数的 unit 由命中规则的 output_parameters 声明（见
 *     GET /v1/scene-rules/export 导出的 v2 YAML：features/output_parameters
 *     中的 unit 字段），本 DTO 不逐键重复。
 *   - 置信度 confidence ∈ [0,1]，无单位。
 *
 * @feature: ingestion
 */
@Data
public class DetectedSceneDTO {

    /** 场景 UUID（DB 主键；引擎原始段序号不入库） */
    private String sceneId;

    /** 所属切片 UUID */
    private String sliceId;

    /**
     * 场景类型：lane_change | car_following | cut_in | overtake | free_flow（可扩展）
     *
     * <p>整包导出模式下（流水线不跑 scene_detector.py、detected_scene 表为空）为
     * {@code full_export}：非规则切分出的驾驶事件，而是本次导出的完整时间线（1 包 1 单元）。</p>
     */
    private String sceneType;

    /** 场景起始时间，UTC RFC3339 */
    private String startTime;

    /** 场景结束时间，UTC RFC3339 */
    private String endTime;

    /** 持续时长（秒），= end - start */
    private Double durationS;

    /** 场景参数（规则 output_parameters 聚合结果，unit 见规则声明） */
    private Map<String, Object> parameters;

    /** 置信度 0-1 */
    private Double confidence;

    /** 命中的场景规则 UUID（scene_rule 记录，可空=规则已删除/历史数据） */
    private String ruleId;

    /** 记录创建时间，UTC RFC3339 */
    private String createdAt;
}
