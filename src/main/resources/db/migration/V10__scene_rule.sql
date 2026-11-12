-- ============================================================
-- DIMS DDL Migration
-- Version: V10
-- Description: 场景规则入库（scene_rule + scene_rule_config + 初始数据）
-- ============================================================
-- 背景：场景规则引擎配置（tools/scene_extraction/rules/scene_rules.yaml）
--   入库管理，支持运行时增删改查、启用/禁用、优先级调整，
--   并通过 GET /v1/scene-rules/export 输出与 YAML 等价的 JSON/YAML
--   供 Python 规则引擎（tools/scene_extraction/scene_detector.py）消费，
--   规则修改无需改 Python 代码。
--
-- 命名说明：任务原始编号 V2 与既有迁移 V2__phase2_new_tables.sql 冲突，
--   本项目迁移按 V{N} 顺序递增编号，故采用 V10。
--
-- scene_rule.conditions / time_window / output_params 为 MySQL JSON 列：
--   conditions:    [{field, operator, value?, unit?, description?}, ...]（AND 逻辑）
--   time_window:   {min_duration_s, max_duration_s, description?}
--   output_params: ["start_time", ...]
-- 结构与 scene_rules.yaml 的 rules[].conditions / time_window / output_parameters
-- 一一对应，坐标系/单位沿用源 YAML 声明（长度 m、速度 m/s、角度 deg/rad/s）。
--
-- 初始数据由 scene_rules.yaml v1.0（2026-08-08）迁移：
--   5 条规则（priority 取 YAML priority.order 的 rank：cut_in=1 最高 … free_flow=5 最低）
--   5 条阈值配置（thresholds 段）
-- ============================================================

-- ============================================================
-- 场景判断规则主表
-- ============================================================
CREATE TABLE scene_rule (
    id            VARCHAR(36)  PRIMARY KEY,
    scene_type    VARCHAR(32)  NOT NULL, -- 场景类型 key（lane_change 等），唯一
    description   VARCHAR(255), -- 场景描述
    reference     VARCHAR(255), -- 参考标准/来源（如 NHTSA Pre-Crash Scenario 分类）
    priority      INT          NOT NULL DEFAULT 99, -- 优先级 rank，数值越小越优先
    is_enabled    BOOLEAN   NOT NULL DEFAULT TRUE, -- 是否启用：1 启用 / 0 禁用
    version       VARCHAR(16)  DEFAULT '1.0', -- 规则版本
    conditions    JSONB         NOT NULL, -- 触发条件数组（AND 逻辑）
    time_window   JSONB         NOT NULL, -- 时间窗口 {min_duration_s, max_duration_s}
    output_params JSONB         NOT NULL, -- 输出场景特征参数名数组
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_scene_type UNIQUE (scene_type)
);
CREATE INDEX idx_priority ON scene_rule (priority);
CREATE INDEX idx_is_enabled ON scene_rule (is_enabled);

-- ============================================================
-- 场景规则全局阈值配置表
-- ============================================================
CREATE TABLE scene_rule_config (
    id           VARCHAR(36)  PRIMARY KEY,
    config_key   VARCHAR(64)  NOT NULL, -- 阈值 key（如 lane_width_typical），唯一
    config_value DOUBLE PRECISION       NOT NULL, -- 阈值数值
    unit         VARCHAR(32), -- 单位（m / s / m/s / rad/s 等）
    description  VARCHAR(255), -- 阈值说明
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_key UNIQUE (config_key)
);

-- ============================================================
-- 初始数据：5 条规则（从 scene_rules.yaml v1.0 迁移）
--   priority 对应 YAML priority.order：
--     cut_in=1（安全关键最高）→ overtake=2 → lane_change=3 → car_following=4 → free_flow=5
-- ============================================================
INSERT INTO scene_rule (id, scene_type, description, reference, priority, is_enabled, version, conditions, time_window, output_params) VALUES
('5c1a2b3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e', 'lane_change', 'Ego车辆换道场景（横向位移超过车道宽度）', 'NHTSA Pre-Crash Scenario: LCM (Lane Change/Merge)', 3, TRUE, '1.0',
 '[{"field":"ego.lateral_displacement","operator":">","value":3.0,"unit":"m","description":"横向累计位移超过3米（典型车道宽度3.5-3.75m）"},{"field":"ego.velocity.vx","operator":">","value":5.0,"unit":"m/s","description":"纵向速度大于5 m/s（排除静止状态）"},{"field":"ego.orientation.yaw_rate","operator":"abs_>","value":0.05,"unit":"rad/s","description":"偏航角速度变化（检测转向动作）"},{"field":"ego.orientation.yaw_change","operator":"abs_<","value":10,"unit":"deg","description":"窗口内净偏航角变化小于10度（换道结束时方向与起始近似平行；排除路口转弯等道路曲线）"}]',
 '{"min_duration_s":3,"max_duration_s":8,"description":"典型换道持续3-8秒"}',
 '["start_time","end_time","lateral_distance","duration","avg_speed","direction","max_yaw_rate"]'),
('6d2b3c4f-5a6b-4c7d-8e8f-0a1b2c3d4e5f', 'car_following', 'Ego跟随前车行驶', 'NHTSA Pre-Crash Scenario: LVM (Lead Vehicle Moving)', 4, TRUE, '1.0',
 '[{"field":"front_vehicle.distance","operator":"<","value":50.0,"unit":"m","description":"前车距离小于50米"},{"field":"front_vehicle.distance","operator":">","value":5.0,"unit":"m","description":"前车距离大于5米（安全距离）"},{"field":"front_vehicle.relative_speed","operator":"abs_<","value":3.0,"unit":"m/s","description":"相对速度小于3 m/s（速度接近）"},{"field":"ego.velocity.vx","operator":">","value":5.0,"unit":"m/s","description":"ego速度大于5 m/s（非静止）"},{"field":"front_vehicle.lateral_offset","operator":"abs_<","value":1.5,"unit":"m","description":"前车横向偏移小于1.5米（同车道）"}]',
 '{"min_duration_s":5,"max_duration_s":600,"description":"跟车至少持续5秒，最长10分钟"}',
 '["start_time","end_time","front_vehicle_id","avg_distance","min_distance","avg_ttc","duration"]'),
('7e3c4d5a-6b7c-4d8e-9f0a-1b2c3d4e5f6a', 'cut_in', '他车从侧方切入ego前方', 'NHTSA Pre-Crash Scenario: LT (Lead Vehicle Turning)', 1, TRUE, '1.0',
 '[{"field":"target_vehicle.initial_lateral_position","operator":"abs_>","value":2.0,"unit":"m","description":"初始横向位置在ego侧方（相邻车道）"},{"field":"target_vehicle.final_lateral_position","operator":"abs_<","value":1.5,"unit":"m","description":"最终横向位置在ego前方（同车道）"},{"field":"target_vehicle.relative_x","operator":"change_from_side_to_front","description":"纵向位置从侧方/后方移动到ego前方"},{"field":"target_vehicle.min_ttc","operator":"<","value":5.0,"unit":"s","description":"最小碰撞时间小于5秒（危险接近）"}]',
 '{"min_duration_s":2,"max_duration_s":5,"description":"加塞动作通常2-5秒完成"}',
 '["start_time","end_time","cut_in_vehicle_id","lateral_intrusion_distance","min_ttc","relative_speed_at_cut_in","final_distance"]'),
('8f4d5e6b-7c8d-4e9f-a0b1-2c3d4e5f6a7b', 'overtake', 'Ego超越前方车辆', '典型超车场景（换道+加速+回归）', 2, TRUE, '1.0',
 '[{"field":"ego.lateral_displacement","operator":">","value":3.0,"unit":"m","description":"ego横向位移超过3米（换道）"},{"field":"target_vehicle.relative_x","operator":"change_from_front_to_rear","description":"目标车从ego前方移动到后方"},{"field":"ego.velocity.vx","operator":">","value":"target_vehicle.velocity.vx + 2.0","unit":"m/s","description":"ego速度比目标车快至少2 m/s"},{"field":"target_vehicle.initial_distance","operator":"<","value":80.0,"unit":"m","description":"初始距离小于80米（可见范围）"}]',
 '{"min_duration_s":5,"max_duration_s":15,"description":"超车过程5-15秒"}',
 '["start_time","end_time","overtaken_vehicle_id","overtake_duration","speed_difference","lateral_clearance","overtake_direction"]'),
('9a5e6f7c-8d9e-4f0a-b1c2-3d4e5f6a7b8c', 'free_flow', '无明显交互的自由行驶', '无前车约束的巡航场景', 5, TRUE, '1.0',
 '[{"field":"nearby_vehicles.count_in_front","operator":"<","value":1,"description":"前方50米内无车辆或仅1辆且距离>50m"},{"field":"ego.velocity.vx","operator":">","value":10.0,"unit":"m/s","description":"速度大于10 m/s（36 km/h）"},{"field":"ego.acceleration.ax","operator":"abs_<","value":1.0,"unit":"m/s^2","description":"纵向加速度稳定（小于1 m/s²）"},{"field":"ego.orientation.yaw_rate","operator":"abs_<","value":0.1,"unit":"rad/s","description":"偏航角速度稳定（直线行驶）"}]',
 '{"min_duration_s":10,"max_duration_s":600,"description":"自由流动至少持续10秒"}',
 '["start_time","end_time","avg_speed","max_speed","duration","distance_traveled"]');

-- ============================================================
-- 初始数据：5 条阈值配置（从 scene_rules.yaml v1.0 thresholds 段迁移）
-- ============================================================
INSERT INTO scene_rule_config (id, config_key, config_value, unit, description) VALUES
('0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d', 'lane_width_typical',        3.5,  'm',   '典型车道宽度（米）'),
('1b2c3d4e-5f6a-4b7c-9d0e-1f2a3b4c5d6e', 'safe_following_distance',   5.0,  'm',   '安全跟车距离（米）'),
('2c3d4e5f-6a7b-4c8d-a0e1-2f3a4b5c6d7e', 'cut_in_ttc_threshold',      5.0,  's',   '加塞TTC阈值（秒）'),
('3d4e5f6a-7b8c-4d9e-b1f2-3a4b5c6d7e8f', 'overtake_speed_diff',       2.0,  'm/s', '超车速度差阈值（m/s）'),
('4e5f6a7b-8c9d-4ea0-a2a3-4b5c6d7e8f9a', 'free_flow_detection_range', 50.0, 'm',   '自由流动检测范围（米）');

