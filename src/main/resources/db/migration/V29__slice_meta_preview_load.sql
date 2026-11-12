-- ============================================================
-- DIMS DDL Migration
-- Version: V29
-- Description: MMT 数据切片「元数据先行 + 按需加载」
-- ============================================================
-- 背景：BOS 定时下载调度此前会把整个 MD5 子集目录全量拉到本地
--   再走完整导入流水线（转换/解析/打标/视频/场景），磁盘与流水线
--   压力大。本次改造为两级加载：
--     1) 调度阶段仅拉取 meta（事件统计元信息）+ 前视鱼眼 h265，
--        解析 meta 建切片（status=META_READY），并把鱼眼转码为
--        可播放 mp4 预览（videos/{sliceId}/preview_front_fisheye.mp4）；
--     2) 用户在数据浏览器点击「加载」时，后端再按本列记录的 BOS
--        会话对象前缀全量拉取文件并复用现有导入流水线。
--
--   data_slice 新增列：
--       import_bos_key   BOS 上该切片会话目录的对象前缀，
--                        如 jiangling-MMT-CX835-ICA/20260902/<md5>/<session>
--                        （仅 MMT meta-only 导入的切片会写入，其余来源为 NULL）
--   状态新增取值（status 为 VARCHAR，无需改类型）：
--       META_READY       仅元数据就绪（未加载全量数据，可播放鱼眼预览）
--       LOADING          用户触发加载后，BOS 全量拉取进行中
-- ============================================================

ALTER TABLE data_slice ADD COLUMN IF NOT EXISTS import_bos_key VARCHAR(512);

COMMENT ON COLUMN data_slice.import_bos_key IS 'BOS 会话对象前缀（MMT meta-only 导入记录，按需全量拉取用），如 <bosPrefix>/<yyyyMMdd>/<md5>/<sessionDir>';
