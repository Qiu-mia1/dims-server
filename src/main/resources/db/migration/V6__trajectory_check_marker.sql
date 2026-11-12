-- ============================================================
-- DIMS DDL Migration
-- Version: V6
-- Description: data_slice 增加 GPS 轨迹提取结果标记列
-- ============================================================
-- 背景：sensor_stream 判定有 GPS 能力（如 odometry/heading 被归为
--   gnss_imu）但 Python extract_gps_trajectory.py 提取结果为 0 点
--   （GNSS_KEYWORDS 不含 odometry/heading）时，若不持久化"已确认空"
--   标记，GET /v1/data-slices/{id}/trajectory 每次都会重新触发异步提取，
--   造成永久循环（每次拉起最长 300s 的 Python 进程）。
--
-- trajectory_status 取值：
--   NULL       未提取过（默认）
--   EXTRACTED  提取成功且有点（gps_trajectory 有缓存点）
--   EMPTY      提取成功但 0 点（已确认为空，不再重复触发提取）
-- trajectory_checked_at：最近一次轨迹提取完成时间（UTC）
--
-- 注意：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，迁移按 V2-V5 约定
-- 手动执行一次，幂等性由执行方保证。
-- ============================================================

ALTER TABLE data_slice
    ADD COLUMN trajectory_status VARCHAR(20) NULL, -- 轨迹提取标记: NULL=未提取|EXTRACTED=已提取|EMPTY=确认无GPS
    ADD COLUMN trajectory_checked_at TIMESTAMP NULL; -- 最近一次轨迹提取完成时间 UTC

