-- ============================================================
-- DIMS DDL Migration（PostgreSQL 方言）
-- Version: V24
-- Description: data_slice 增加 trigger_time 列（触发时间）
-- ============================================================
-- 背景：CFDI meta 文件根级字段 trigger_timestamp_us（数据面本地时钟，微秒）
--   与 utc_diff_us（本地时钟 → UTC 偏移，微秒，V18 已落库）之和，即事件
--   真实 UTC 触发时刻。本地目录导入 LocalDirectoryImportServiceImpl#handleSession
--   解析 meta 时换算该时刻并落库，供数据浏览器「触发时间」列展示与排序。
--
-- 字段说明：
--   trigger_time  TIMESTAMP 可空，存 UTC（与 start_ts/end_ts 语义一致）；
--                 非 CFDI 来源 / meta 缺失 / 关键字段非法时为 NULL。
--                 前端展示时按东八区（北京时间 UTC+8）呈现。
--
-- 注意：本项目无 Flyway 自动执行机制，本脚本需手动 apply。
-- ============================================================

ALTER TABLE data_slice
    ADD COLUMN trigger_time TIMESTAMP NULL; -- 触发时间（UTC）：meta.trigger_timestamp_us + utc_diff_us

CREATE INDEX idx_data_slice_trigger_time ON data_slice (trigger_time);
