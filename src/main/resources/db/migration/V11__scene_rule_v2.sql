-- ============================================================
-- DIMS DDL Migration
-- Version: V11
-- Description: scene_rule v2 化 — 配置驱动引擎（tools/scene_extraction v2）完整规则结构
-- ============================================================
-- 背景：场景提取引擎完成 v2 重构（engine/model.py + engine/rules/），
--   规则从 v1 的 conditions + time_window + output_params 升级为：
--     logic / confidence / entity_roles / output_parameters /
--     postprocess / features / min_engine_version
--   v2 YAML 实例见 tools/scene_extraction/rules/cut_in.yaml。
--
-- 兼容性：
--   - 保留旧列 output_params 不动（已有初始数据），新增 output_parameters 列
--   - conditions / time_window 语义兼容（v1 元素结构是 v2 的子集：
--     value_type 缺省 literal、params 缺省空 map、negate 缺省 false）
--   - 新增列均有 NOT NULL DEFAULT / DEFAULT，存量行自动补齐
--
-- 初始数据对齐：5 条初始规则的 conditions / time_window / entity_roles /
--   output_parameters / postprocess / confidence 对齐 tools/scene_extraction/
--   rules/*.yaml 的 v2 语义（DB 为规则管理源，导出即 v2 YAML，Python
--   RuleLoader 可直接加载）。
-- ============================================================

-- ============================================================
-- 1. scene_rule 新增 v2 列
-- ============================================================
ALTER TABLE scene_rule
    ADD COLUMN logic            VARCHAR(8)  NOT NULL DEFAULT 'all', -- 条件逻辑：all(AND) 或 any(OR)
    ADD COLUMN confidence       DOUBLE PRECISION      NOT NULL DEFAULT 0.85, -- 置信度
    ADD COLUMN entity_roles     JSONB, -- 实体角色声明 {role_name: {selector, types}}
    ADD COLUMN output_parameters JSONB, -- 输出参数数组 [{name, feature, aggregate, round}]，替代原 output_params
    ADD COLUMN postprocess      JSONB, -- 后处理声明 [{type, params}]
    ADD COLUMN features         JSONB, -- 声明式复合特征 [{name, expression, unit}]
    ADD COLUMN min_engine_version VARCHAR(16) DEFAULT '2.0'; -- 最低引擎版本门禁

-- v1 遗留列 output_params 放宽为 NULL（v2 客户端只传 output_parameters，
-- 不传旧字段 → 实体 outputParams=null → MyBatis-Plus NOT_NULL 策略省略该列
-- → 旧约束 NOT NULL 会触发 MySQL 1048 "cannot be null"）
ALTER TABLE scene_rule ALTER COLUMN output_params DROP NOT NULL; -- v1遗留输出参数字符串数组（v2用output_parameters替代）

-- ============================================================
-- 2. 初始数据对齐 v2 语义（5 条规则）
--    conditions / time_window / entity_roles / output_parameters /
--    postprocess / confidence 与 tools/scene_extraction/rules/*.yaml 一致
--    旧列 output_params 保留不动
-- ============================================================

-- 2.1 lane_change：纯 ego 条件，无实体角色；输出参数 v2 化
--     （v2 YAML：yaw_rate 掩码阈值 0.05→0.02 + 段级窗口条件 max_yaw_rate）
UPDATE scene_rule
SET description        = 'Ego车辆换道场景（横向位移超过车道宽度）',
    confidence         = 0.85,
    logic              = 'all',
    min_engine_version = '2.0',
    entity_roles       = NULL,
    conditions         = '[{"field":"ego.lateral_displacement","operator":">","value":3.0,"value_type":"literal","unit":"m"},{"field":"ego.velocity.vx","operator":">","value":5.0,"value_type":"literal","unit":"m/s"},{"field":"ego.orientation.yaw_rate","operator":"abs_>","value":0.02,"value_type":"literal","unit":"rad/s"},{"field":"ego.orientation.max_yaw_rate","operator":">","value":0.05,"value_type":"literal","unit":"rad/s"},{"field":"ego.orientation.yaw_change","operator":"abs_<","value":10,"value_type":"literal","unit":"deg"}]',
    time_window        = '{"min_duration_s":3,"max_duration_s":8}',
    output_parameters  = '[{"name":"lateral_distance","feature":"ego.lateral_displacement","round":2},{"name":"avg_speed","feature":"ego.velocity.vx","aggregate":"mean","round":2},{"name":"direction","feature":"ego.lateral_displacement_direction"},{"name":"max_yaw_rate","feature":"ego.orientation.max_yaw_rate","round":4}]',
    postprocess        = '[{"type":"merge_overlapping","params":{"tolerance_s":2.0}},{"type":"trim_to_maneuver","params":{"lateral_rate":0.15,"margin_s":0.5}}]'
WHERE scene_type = 'lane_change';

-- 2.2 car_following：front_vehicle 角色（候选池 + nearest_in_front 选择器）
UPDATE scene_rule
SET description        = 'Ego跟随前车行驶（前车距离 5-50m、同车道、速度接近）',
    confidence         = 0.88,
    logic              = 'all',
    min_engine_version = '2.0',
    entity_roles       = '{"front_vehicle":{"selector":"nearest_in_front","types":["vehicle"]}}',
    conditions         = '[{"field":"front_vehicle.distance","operator":"within","value":[5.0,50.0],"value_type":"range","unit":"m"},{"field":"front_vehicle.lateral_offset","operator":"abs_<","value":1.5,"value_type":"literal","unit":"m"},{"field":"front_vehicle.relative_speed_signed","operator":"abs_<","value":3.0,"value_type":"literal","unit":"m/s"},{"field":"ego.velocity.vx","operator":">","value":5.0,"value_type":"literal","unit":"m/s"}]',
    time_window        = '{"min_duration_s":5,"max_duration_s":600}',
    output_parameters  = '[{"name":"front_vehicle_id","feature":"front_vehicle.id","aggregate":"last"},{"name":"avg_distance","feature":"front_vehicle.distance","aggregate":"mean","round":2},{"name":"min_distance","feature":"front_vehicle.distance","aggregate":"min","round":2}]',
    postprocess        = '[{"type":"merge_overlapping","params":{"tolerance_s":2.0}}]'
WHERE scene_type = 'car_following';

-- 2.3 cut_in：target_vehicle 角色 + 段级 pattern 算子 change_from_side_to_front
--     （v2 YAML：候选池 ±相邻车道带 |y|<4.5、距离 5-60m；时间窗 2-10s）
UPDATE scene_rule
SET description        = '他车从侧方切入ego前方（横向偏移由相邻车道带进入本车道）',
    confidence         = 0.85,
    logic              = 'all',
    min_engine_version = '2.0',
    entity_roles       = '{"target_vehicle":{"selector":"nearest_side_in_front","types":["vehicle"]}}',
    conditions         = '[{"field":"target_vehicle.relative_x","operator":">","value":0.0,"value_type":"literal","unit":"m","description":"纵向在本车前方"},{"field":"target_vehicle.lateral_offset","operator":"abs_<","value":4.5,"value_type":"literal","unit":"m","description":"本车道或相邻车道带（|y|<4.5）"},{"field":"target_vehicle.distance","operator":"within","value":[5.0,60.0],"value_type":"range","unit":"m","description":"与本车距离在合理范围"},{"field":"target_vehicle.lateral_offset","operator":"change_from_side_to_front","params":{"side_threshold":2.0,"front_threshold":1.5},"description":"目标从相邻车道移动到本车道"},{"field":"ego.velocity.vx","operator":">","value":1.0,"value_type":"literal","unit":"m/s","description":"本车非静止"}]',
    time_window        = '{"min_duration_s":2,"max_duration_s":10}',
    output_parameters  = '[{"name":"cut_in_vehicle_id","feature":"target_vehicle.id","aggregate":"last"},{"name":"final_distance","feature":"target_vehicle.distance","aggregate":"last","round":2},{"name":"min_distance","feature":"target_vehicle.distance","aggregate":"min","round":2},{"name":"relative_speed","feature":"target_vehicle.relative_speed_signed","aggregate":"mean","round":2}]',
    postprocess        = '[{"type":"merge_overlapping","params":{"tolerance_s":2.0}}]'
WHERE scene_type = 'cut_in';

-- 2.4 overtake：target_vehicle 角色 + 段级 pattern 算子 change_from_front_to_rear
--     （v2 YAML：相对 x ∈ [-20, 80]m 候选池、本车保持车道；时间窗 3-15s）
UPDATE scene_rule
SET description        = 'Ego直线加速超越前方车辆（相对纵向距离由正跨负）',
    confidence         = 0.85,
    logic              = 'all',
    min_engine_version = '2.0',
    entity_roles       = '{"target_vehicle":{"selector":"nearest_in_front","types":["vehicle"]}}',
    conditions         = '[{"field":"target_vehicle.relative_x","operator":"within","value":[-20.0,80.0],"value_type":"range","unit":"m","description":"前方或刚被超越（±20m 内），候选段须覆盖 +→− 跨越点"},{"field":"target_vehicle.lateral_offset","operator":"abs_<","value":1.5,"value_type":"literal","unit":"m","description":"同车道"},{"field":"target_vehicle.relative_speed_signed","operator":"<","value":0.0,"value_type":"literal","unit":"m/s","description":"本车速度 > 目标速度（纵向接近中）"},{"field":"target_vehicle.relative_x","operator":"change_from_front_to_rear","description":"目标车从本车前方移动到后方"},{"field":"ego.velocity.vx","operator":">","value":5.0,"value_type":"literal","unit":"m/s"},{"field":"ego.lateral_displacement","operator":"<","value":1.5,"value_type":"literal","unit":"m"}]',
    time_window        = '{"min_duration_s":3,"max_duration_s":15}',
    output_parameters  = '[{"name":"overtaken_vehicle_id","feature":"target_vehicle.id","aggregate":"last"},{"name":"relative_speed","feature":"target_vehicle.relative_speed_signed","aggregate":"mean","round":2},{"name":"min_relative_x","feature":"target_vehicle.relative_x","aggregate":"min","round":2}]',
    postprocess        = '[{"type":"merge_overlapping","params":{"tolerance_s":2.0}}]'
WHERE scene_type = 'overtake';

-- 2.5 free_flow：纯 scalar 条件，无实体角色
--     （v2 YAML：nearby_vehicles.count<3 + vx>10，无 unit 的计数条件不设 unit）
UPDATE scene_rule
SET description        = '无明显交互的自由行驶',
    confidence         = 0.90,
    logic              = 'all',
    min_engine_version = '2.0',
    entity_roles       = NULL,
    conditions         = '[{"field":"nearby_vehicles.count","operator":"<","value":3,"value_type":"literal"},{"field":"ego.velocity.vx","operator":">","value":10.0,"value_type":"literal","unit":"m/s"}]',
    time_window        = '{"min_duration_s":10,"max_duration_s":600}',
    output_parameters  = '[{"name":"avg_speed","feature":"ego.velocity.vx","aggregate":"mean","round":2},{"name":"max_speed","feature":"ego.velocity.vx","aggregate":"max","round":2}]',
    postprocess        = '[{"type":"merge_overlapping","params":{"tolerance_s":2.0}}]'
WHERE scene_type = 'free_flow';

