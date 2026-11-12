-- ============================================================
-- V5: CFDI 切片 file_path 修复
-- ============================================================
-- 背景：历史 CFDI 切片在 filePath 修复前的记录仍指向
--   data/cfdi/ 路径和 original.tar.gz 扩展名，而非正确的
--   data/bags/ 路径和 original.bag 扩展名。
--
-- 修复：同时替换路径前缀和文件名后缀。
-- WHERE 条件严格限定于 source_type='CFDI' 且含 'tar.gz' 的记录。
-- ============================================================

UPDATE data_slice
SET file_path = REPLACE(REPLACE(file_path, 'data/cfdi/', 'data/bags/'), 'original.tar.gz', 'original.bag')
WHERE source_type = 'CFDI'
  AND file_path LIKE '%tar.gz';
