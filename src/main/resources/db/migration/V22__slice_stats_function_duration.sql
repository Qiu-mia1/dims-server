-- Version: V22
-- Description: slice_stats 补充分功能运行里程/时长/激活次数列（AEB/APA/ACC/LCC+ 全链路统计）
--
-- 背景：驾驶舱 func-detail 目前仅 NOA 有完整统计（noa_activation_count /
-- noa_total_duration_s / noa_mileage_km），AEB/APA 只有触发/任务次数，
-- ACC/LCC 只有布尔激活标志。为让 AEB/APA/ACC/LCC+ 也能像 NOA 一样展示
-- 「激活次数 · 运行里程 · 平均时长 · 接管介入次数」，Phase 2.1 Python
-- 提取算法（extract_dashboard_stats.py）据此版本追加输出以下字段，本迁移
-- 在 slice_stats 上补充对应持久化列。
--
-- 单位约定（宪法第 7 节）：时长 unit: s，里程 unit: km，时间字段一律 UTC。

ALTER TABLE slice_stats
  ADD COLUMN aeb_total_duration_s DOUBLE PRECISION,        -- AEB 激活总时长, unit: s
  ADD COLUMN aeb_mileage_km DOUBLE PRECISION,              -- AEB 激活期间里程, unit: km
  ADD COLUMN apa_total_duration_s DOUBLE PRECISION,        -- APA 任务激活总时长, unit: s
  ADD COLUMN apa_mileage_km DOUBLE PRECISION,              -- APA 激活期间里程, unit: km
  ADD COLUMN acc_activation_count INT DEFAULT 0,           -- ACC 激活次数
  ADD COLUMN acc_total_duration_s DOUBLE PRECISION,        -- ACC 激活总时长, unit: s
  ADD COLUMN acc_mileage_km DOUBLE PRECISION,              -- ACC 激活期间里程, unit: km
  ADD COLUMN lcc_activation_count INT DEFAULT 0,           -- LCC+ 激活次数
  ADD COLUMN lcc_total_duration_s DOUBLE PRECISION,        -- LCC+ 激活总时长, unit: s
  ADD COLUMN lcc_mileage_km DOUBLE PRECISION;              -- LCC+ 激活期间里程, unit: km

ALTER TABLE public.data_slice ADD event_name varchar(1024) NULL;
COMMENT ON COLUMN public.data_slice.event_name IS '触发类型';
CREATE INDEX data_slice_event_name_idx ON public.data_slice (event_name);
