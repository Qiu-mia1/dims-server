-- ============================================================
-- DIMS DDL Migration
-- Version: V12
-- Description: 场景提取任务表 + 已检测场景表（场景提取结果持久化）
-- ============================================================
-- 背景：DEVELOPMENT.md §5.6（场景提取模块）/ docs/scene_extraction_design.md。
--   Pipeline（extract_gps_trajectory → extract_perception →
--   bag_to_unified_json → scene_detector）产出 detected_scenes.json，
--   本迁移将任务状态机与场景结果持久化到 MySQL，供 API 查询与下游 SIMS 消费。
--
-- scene_extraction_task 状态机（与通用 TaskStatus 语义不同，勿混用）：
--   PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次）
-- intermediate_paths 为 JSON 数组：管道各步产物绝对路径
--   [ego_trajectory_raw.json, perception/, scene_data.json, detected_scenes.json]
-- output_path 指向引擎主产物 detected_scenes.json 的绝对路径
-- rule_version 为规则格式版本（v2 配置驱动引擎 = 2），
-- engine_version 为启用规则的最高 min_engine_version 门禁（如 "2.0"）
--
-- detected_scene.external_scene_id 为引擎输出段的原始 scene_id（段序号），
-- 本表主键 id 为 DB 生成的 UUID（API 对外使用）。
-- parameters 为规则 output_parameters 聚合结果（自由结构，unit 由规则声明）。
--
-- 注意：MySQL 8.0 不支持 CREATE TABLE IF NOT EXISTS 之外的幂等写法，
-- 新表一次性创建，不涉及存量数据迁移。
-- ============================================================

CREATE TABLE scene_extraction_task (
    id                  VARCHAR(36)   PRIMARY KEY,
    slice_id            VARCHAR(36)   NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- PENDING|RUNNING|SUCCESS|FAILED
    progress            INT           DEFAULT 0, -- 进度 0-100
    total_scenes        INT           DEFAULT 0, -- 检测到的场景总数（SUCCESS 时有值）
    output_path         VARCHAR(500), -- 主产物 detected_scenes.json 绝对路径
    intermediate_paths  JSONB, -- 管道各步产物绝对路径数组
    rule_version        INT, -- 规则格式版本（v2=2）
    engine_version      VARCHAR(20), -- 最低引擎版本门禁（启用规则 max min_engine_version）
    error_msg           VARCHAR(2000), -- 错误信息（FAILED 时非空）
    created_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP(3), -- 任务结束时间 UTC（SUCCESS/FAILED 时写入）
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_scene_extraction_task_slice_status ON scene_extraction_task (slice_id, status);
CREATE INDEX idx_scene_extraction_task_created_at ON scene_extraction_task (created_at);

CREATE TABLE detected_scene (
    id                 VARCHAR(36)   PRIMARY KEY,
    task_id            VARCHAR(36)   NOT NULL,
    slice_id           VARCHAR(36)   NOT NULL,
    external_scene_id  VARCHAR(100), -- 引擎输出段原始 scene_id（段序号）
    scene_type         VARCHAR(32)   NOT NULL, -- lane_change|car_following|cut_in|overtake|free_flow（可扩展）
    start_time         TIMESTAMP(3)   NOT NULL, -- 场景起始时间 UTC（bag 时间轴，毫秒精度）
    end_time           TIMESTAMP(3)   NOT NULL, -- 场景结束时间 UTC（bag 时间轴，毫秒精度）
    duration_s         DOUBLE PRECISION        NOT NULL, -- 持续时长（秒）= end - start
    confidence         DOUBLE PRECISION        NOT NULL DEFAULT 0.0, -- 置信度 0-1
    parameters         JSONB, -- 规则 output_parameters 聚合结果（unit 由规则声明）
    rule_id            VARCHAR(36), -- 命中的 scene_rule 记录（可空=内置规则/规则已删除）
    created_at         TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES scene_extraction_task(id) ON DELETE CASCADE,
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_slice_type ON detected_scene (slice_id, scene_type);
CREATE INDEX idx_detected_scene_task_id ON detected_scene (task_id);

