-- ============================================================
-- DIMS DDL Migration  Version: V29
-- Description: 标签数据全部来源 SIMS —— 移除本地 tag 表
-- 设计依据：docs/superpowers/specs/2026-09-23-dims-tag-management-refactor.md
-- ============================================================
-- 背景（2026-09-23 需求确认）：
--   标签数据「定义集中、引用分散」——标签定义/分类树全部由 SIMS 片段域
--   （scope=FRAGMENT）托管，DIMS 只做代理调用，不再本地建表、不再定时同步。
--   本地 tag 表随之移除；data_slice_tag 只保留引用（tag_id = SIMS 标签 UUID）。
--
-- ⚠️ 为什么必须补 data_slice_tag.created_at：
--   现有 SliceTagResponse.timestamp 取的是 tag.created_at（标签【定义】创建时间），
--   语义本就错误。tag 表删除后该列无处可取 → 本脚本让打标关联自带时间，
--   timestamp 语义修正为「打标关联建立时间」。
--
-- ⚠️ 为什么先 DROP 外键：
--   data_slice_tag.tag_id 的外键名 data_slice_tag_ibfk_2 写死在 V1 基线里
--   （REFERENCES tag(id) ON DELETE CASCADE），不摘掉外键无法 DROP TABLE tag。
--
-- ⚠️ 执行顺序：**先 apply 本脚本，再发布新后端**。
--   反向（先发版后 apply）新代码不引用 tag 表，可运行；
--   但旧代码在表被删后会直接报 relation "tag" does not exist。
--   Flyway 在本项目不自动执行，需手工 apply（见 DEVELOPMENT.md 标签章节）。
--
-- ⚠️ 历史脏引用（本脚本**不自动清理**）：
--   V20 一次性导入的 301 行是【场景域】目录，切到 FRAGMENT 后不再由任何域供给。
--   删表后这些 data_slice_tag 引用会解析成「未知标签」占位（不报错、不阻塞）。
--   若要清理，先核对引用量再执行（删了不可逆）：
--     SELECT count(*) AS refs FROM data_slice_tag dst
--      WHERE dst.tag_id IN (SELECT id FROM tag WHERE scope IS NULL OR scope <> 'FRAGMENT');
--     DELETE FROM data_slice_tag dst
--      WHERE dst.tag_id IN (SELECT id FROM tag WHERE scope IS NULL OR scope <> 'FRAGMENT');
--
-- 幂等性：三段均带 IF NOT EXISTS / IF EXISTS，重复执行纯 no-op。
-- ============================================================

ALTER TABLE data_slice_tag ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE data_slice_tag DROP CONSTRAINT IF EXISTS data_slice_tag_ibfk_2;

DROP TABLE IF EXISTS tag;

COMMENT ON COLUMN data_slice_tag.created_at IS '打标关联建立时间（V29 起自持；此前取自 tag.created_at）';
