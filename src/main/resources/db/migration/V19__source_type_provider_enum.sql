-- ============================================================
-- DIMS DDL Migration
-- Version: V19
-- Description: source_type 语义替换 —— 数据格式(BAG|CFDI|LOCAL) → 数据来源提供方(MMT|ZYT|Hirain)
-- ============================================================
-- 背景：上传接口的来源字段改为前端传参，枚举 MMT/ZYT/Hirain（数据提供方），
--   不再由后端按文件扩展名判定。文件上传与目录导入两个入口均适用。
--
-- 内部"是否走 CFDI 链路"的判别改用 cfdi_meta 表存在性：
--   CFDI 上传（CfdiPipelineServiceImpl）与 LOCAL 目录导入
--   （LocalDirectoryImportPipelineServiceImpl）两条链路均写 cfdi_meta，
--   与旧值 IN ('CFDI','LOCAL') 语义完全一致。
--
-- 存量数据：旧值 BAG/CFDI/LOCAL 一律置 NULL（前端展示「未知来源」）。
-- ============================================================

-- 1. 解除 NOT NULL 与旧默认值（存量数据将置 NULL）
ALTER TABLE data_slice ALTER COLUMN source_type DROP NOT NULL;
ALTER TABLE data_slice ALTER COLUMN source_type DROP DEFAULT;

-- 2. 存量旧值置 NULL
UPDATE data_slice SET source_type = NULL WHERE source_type IN ('BAG', 'CFDI', 'LOCAL');

-- 3. 新枚举约束：仅 MMT/ZYT/Hirain（NULL 不违反 CHECK，存量 NULL 可共存）
ALTER TABLE data_slice
  ADD CONSTRAINT chk_data_slice_source_type
  CHECK (source_type IN ('MMT', 'ZYT', 'Hirain'));
