-- ============================================================
-- DIMS DDL Migration
-- Version: V19
-- Description: data_slice 增加 storage_type 列（MinIO 迁移）
-- ============================================================
-- 背景：存储层从本地文件系统迁往 MinIO（dims.storage.type=minio）。
--   存量 19GB 本地数据不迁移（原地归档），仅新数据进 MinIO。
--   storage_type 标记切片数据的存储位置：
--     LOCAL — 本地文件系统（存量记录默认；minio 模式下数据内容读取返回 410 DATA_ARCHIVED）
--     MINIO — MinIO 对象存储（新上传/导入切片，file_path 存相对 key）
--   其余表 file_path 语义升级为存储引用（LOCAL 模式兼容存量绝对路径），不加列。
--
-- 字段说明：
--   storage_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL'
--                LOCAL | MINIO；存量记录保持 LOCAL（数据已归档不迁移）。
-- ============================================================

ALTER TABLE data_slice
    ADD COLUMN storage_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL'; -- 存储模式：LOCAL=本地文件系统（归档）| MINIO=MinIO 对象存储
