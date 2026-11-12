-- ============================================================
-- DIMS DDL Migration
-- Version: V17
-- Description: 本地目录导入（Local Directory Import）支持列
-- ============================================================
-- 背景：用户源数据按 {user_dir}/{project}/{date}/{md5_32hex}/
--   {md5后8位}_{时间戳}_public/public/data/{mfbag,other}/ 目录组织，
--   POST /v1/data-slices/directory-import 直接扫描服务器本地目录批量导入，
--   source_type=LOCAL，后续复用 CFDI 同款 Docker 转换链路。
--
-- 字段说明：
--   vin_md5_id         逻辑外键 → vin_md5_mapping.id（BIGINT，非物理 FK，
--                      MD5 子目录反查 VIN 时记录车辆 ID）
--   vin                VIN 快照（反查失败为 NULL）
--   import_md5         源 MD5 子目录名（32 位 hex）
--   session_id         会话目录名（{8hex}_{digits}_public）
--   import_source_dir  源会话目录绝对路径（只读，流水线复制其 public/ 到工作区）
-- uk_import_session 唯一键：同一 (import_md5, session_id) 只导入一次（去重）；
--   import_md5/session_id 可空（非本地导入记录为 NULL），MySQL 唯一索引
--   允许多个 NULL，不影响 BAG/CFDI 既有记录。
-- ============================================================

ALTER TABLE data_slice
    ADD COLUMN vin_md5_id        BIGINT        NULL, -- 车辆 FK → vin_md5_mapping.id（逻辑外键）
    ADD COLUMN vin               VARCHAR(32)   NULL, -- VIN 快照
    ADD COLUMN import_md5        VARCHAR(64)   NULL, -- 本地导入源 MD5 子目录
    ADD COLUMN session_id        VARCHAR(255)  NULL, -- 本地导入会话目录名
    ADD COLUMN import_source_dir VARCHAR(1024) NULL, -- 本地导入源目录绝对路径
    ADD CONSTRAINT uk_import_session UNIQUE (import_md5, session_id);
CREATE INDEX idx_vin_md5_id ON data_slice (vin_md5_id);

