-- ============================================================
-- DIMS DDL Migration（PostgreSQL 方言）
-- Version: V9
-- Description: export_task 表新增 updated_at 列 + 查询路径索引
-- ============================================================
-- 背景：GET /v1/exports 全局导出任务列表接口需要返回 updated_at
--   （任务状态流转的最近更新时间，如 PENDING → PROCESSING → READY/ERROR）。
-- 定义与 data_slice.updated_at 保持一致：
--   写时由 MyBatis-Plus MetaObjectHandler 自动填充（INSERT_UPDATE），
--   存量行回填 CURRENT_TIMESTAMP。
--
-- 方言说明：MySQL 版用 information_schema + PREPARE/EXECUTE 实现幂等
--   （MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS）；PG 原生支持
--   ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS，直接使用。
--   MySQL 的 ON UPDATE CURRENT_TIMESTAMP 在 PG 无对应物，由应用层填充。
-- ============================================================

-- 1. 新增 updated_at 列（已存在则跳过）
ALTER TABLE export_task
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP; -- 最近更新时间，UTC

-- 2. 查询路径索引（Review W5，已存在则跳过）
--    idx_export_task_status_created：全局列表按 status 过滤 + created_at DESC 排序
--    idx_export_task_slice_created：切片列表按 slice_id 过滤 + created_at DESC 排序
CREATE INDEX IF NOT EXISTS idx_export_task_status_created ON export_task (status, created_at);
CREATE INDEX IF NOT EXISTS idx_export_task_slice_created ON export_task (slice_id, created_at);
