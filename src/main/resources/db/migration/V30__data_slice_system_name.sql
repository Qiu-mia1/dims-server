-- ============================================================
-- DIMS DDL Migration（PostgreSQL 方言）
-- Version: V30
-- Description: data_slice 增加 system_name 列（软件版本号）
-- ============================================================
-- 背景：CFDI meta 文件根级字段 system_name 为事件软件版本号。
--   本地目录导入 LocalDirectoryImportServiceImpl#handleSessionMetaOnly
--   及 #handleSession 解析 meta 时提取并落库，供数据浏览器
--   切片详情页「软件版本」列展示（替换原 utc_diff_us 字段）。
--
-- 字段说明：
--   system_name  VARCHAR(128) 可空，存事件软件版本号；
--                非 CFDI 来源 / meta 缺失 / 字段非法时为 NULL。
--
-- 注意：本项目无 Flyway 自动执行机制，本脚本需手动 apply。
-- ============================================================

ALTER TABLE data_slice
    ADD COLUMN IF NOT EXISTS system_name VARCHAR(128) NULL; -- 软件版本号：meta.system_name

COMMENT ON COLUMN data_slice.system_name IS '软件版本号（CFDI meta 根级字段 system_name），切片详情页展示用，替换 utc_diff_us';
