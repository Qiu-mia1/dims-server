-- ============================================================
-- DIMS DDL Migration
-- Version: V18
-- Description: data_slice 增加 utc_diff_us 列
-- ============================================================
-- 背景：CFDI meta 文件根级字段 utc_diff_us（单位微秒）由
--   parse_cfdi_metadata.py 解析落库到 cfdi_meta.meta_data。
--   真实 UTC = bag 时间戳 + utc_diff_us × 1000（us → ns）。
--   processParsePipeline 调用 parse_rosbag.py 时将该值传入，
--   并在本列持久化，供回放/可视化等下游时间换算复用。
--
-- 字段说明：
--   utc_diff_us  BIGINT 可空，单位微秒；非 CFDI 来源（BAG/LOCAL）
--               meta 缺失时为 NULL。
-- ============================================================

ALTER TABLE data_slice
    ADD COLUMN utc_diff_us BIGINT NULL; -- CFDI meta 时钟偏移（微秒）：bag 时间戳 + utc_diff_us×1000 = 真实 UTC

