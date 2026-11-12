-- ============================================================
-- DIMS DDL Migration
-- Version: V13
-- Description: 新增 approaching（接近前车）场景规则
-- ============================================================
-- 背景：Algorithm Agent 在 tools/scene_extraction/rules/approaching.yaml
--   新增 approaching 场景类型（自车从远处接近前方同向行驶车辆，
--   距离 50-150m，car_following 的前序阶段）。
--
-- 注册方式：scene_rule 表（DB）为规则管理源 —— Pipeline 场景提取时
--   优先将 DB 启用规则导出为 v2 YAML（writeRulesToTempDir → scene_detector.py
--   --rules），内置 tools/scene_extraction/rules/ 目录仅作"无启用规则"
--   兜底。因此 approaching 必须入库，才能：
--     1) GET /v1/scene-rules 可管理（启用/禁用/优先级/更新）
--     2) GET /v1/scene-rules/export 导出给 Python 引擎消费
--
-- 数据对齐 approaching.yaml（2026-08-10）：
--   - entity_roles: front_vehicle（nearest_in_front 选择器 + vehicle 类型）
--   - conditions:   distance ∈ [50,150]m（within）∧ |lateral_offset|<3.5m
--                   ∧ |relative_speed_signed|<8m/s ∧ ego.vx>5m/s
--   - time_window:  3-60s
--   - output_parameters: front_vehicle_id / avg_distance / min_distance /
--                       avg_rel_speed（与 car_following 相同的 4 个输出）
--   - postprocess:  merge_overlapping（tolerance_s=2.0）
--   - priority=5：与 free_flow 同级；低于 car_following(4)，距离带
--     [50,150] vs [5,50] 不重叠，不会双重命中同一时段
--   - reference 置 NULL（源 YAML 未声明参考标准，不臆造）
-- ============================================================

INSERT INTO scene_rule (id, scene_type, description, reference, priority, is_enabled, version, logic, confidence, entity_roles, conditions, time_window, output_parameters, postprocess, features, min_engine_version, output_params) VALUES
('a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d', 'approaching', '自车从远处接近前方同向行驶车辆（距离50-150m，跟车的前序阶段）', NULL, 5, TRUE, '2.0', 'all', 0.82,
 '{"front_vehicle":{"selector":"nearest_in_front","types":["vehicle"]}}',
 '[{"field":"front_vehicle.distance","operator":"within","value":[50.0,150.0],"value_type":"range","unit":"m"},{"field":"front_vehicle.lateral_offset","operator":"abs_<","value":3.5,"value_type":"literal","unit":"m"},{"field":"front_vehicle.relative_speed_signed","operator":"abs_<","value":8.0,"value_type":"literal","unit":"m/s"},{"field":"ego.velocity.vx","operator":">","value":5.0,"value_type":"literal","unit":"m/s"}]',
 '{"min_duration_s":3,"max_duration_s":60}',
 '[{"name":"front_vehicle_id","feature":"front_vehicle.id","aggregate":"last"},{"name":"avg_distance","feature":"front_vehicle.distance","aggregate":"mean","round":2},{"name":"min_distance","feature":"front_vehicle.distance","aggregate":"min","round":2},{"name":"avg_rel_speed","feature":"front_vehicle.relative_speed_signed","aggregate":"mean","round":2}]',
 '[{"type":"merge_overlapping","params":{"tolerance_s":2.0}}]',
 NULL,
 '2.0',
 NULL);
