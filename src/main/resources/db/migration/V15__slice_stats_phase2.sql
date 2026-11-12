-- Version: V15
-- Description: Phase 2 — 驾驶舱统计聚合指标列（状态机事件检测产物）
--
-- Phase 1 的 slice_stats 表已包含 6 个 Phase 2 基础字段列
-- （noa_activation_count/noa_total_duration_s/aeb_trigger_count/
--   apa_task_count/apa_success_count/takeover_count），
-- Phase 2 Python 状态机开始产出实际值，本迁移追加派生指标列。

ALTER TABLE slice_stats
  ADD COLUMN speed_min DOUBLE PRECISION, -- 最低车速, unit: m/s
  ADD COLUMN noa_success_count INT DEFAULT 0, -- NOA 成功退出次数
  ADD COLUMN noa_fail_count INT DEFAULT 0, -- NOA 失败退出次数
  ADD COLUMN noa_exit_count INT DEFAULT 0, -- NOA 总退出次数
  ADD COLUMN noa_mileage_km DOUBLE PRECISION, -- NOA 运行里程, unit: km
  ADD COLUMN noa_success_rate DOUBLE PRECISION, -- NOA 成功率 0-1
  ADD COLUMN aeb_total_scenes INT DEFAULT 0, -- AEB 感知数据覆盖的场景数
  ADD COLUMN apa_success_rate DOUBLE PRECISION, -- APA 泊车成功率 0-1
  ADD COLUMN takeover_rate DOUBLE PRECISION, -- 接管率, unit: 次/100km
  ADD COLUMN takeover_reasons JSONB, -- 接管原因分布 {reason: count}
  ADD COLUMN intervention_rate DOUBLE PRECISION, -- 介入率, unit: 次/100km
  ADD COLUMN scene_breakdown JSONB; -- 按道路场景分组的各功能指标 {scene: {noa_duration_s, takeover_count, ...}}

