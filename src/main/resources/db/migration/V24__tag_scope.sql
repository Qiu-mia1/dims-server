-- ============================================================
-- V24: 标签库接入片段域（SIMS 契约 scope 改造）—— P2
--
-- 背景：sims.openapi.yaml v3.2.0 起 GET /tags 的 scope 为**必选**参数
--       （SCENARIO=场景域 / ASSET=资产域 / FRAGMENT=片段域），三域完全隔离，
--       缺失 scope 返回 400 VALIDATION_ERROR（无默认域兜底）。
--       DIMS 只消费**片段域**词表：DIMS 的「片段」= data_slice，
--       片段打标标签由 SIMS 以 scope=FRAGMENT 托管，本地建引用（定义集中、引用分散）。
--       见 docs/scenario/tag-scope-design.md §3.6 / §5.2。
--
-- 拉取实现：com.isim.dims.service.SimsTagSyncService（scope 由 dims.sims.tag-scope 配置）。
--
-- ⚠️ 执行顺序：**先 apply 本脚本，再部署带 scope 字段的后端**
--    （MyBatis-Plus 会把 scope 列入 SQL，列不存在则标签查询直接报错）。
--    本脚本对旧代码无影响（多一列不被引用）。
-- ============================================================

ALTER TABLE tag ADD COLUMN IF NOT EXISTS scope VARCHAR(20);

-- ---------------------------------------------------------------------------
-- 历史数据说明（不自动执行，需人工确认后放开）
--
-- V20 一次性导入的 301 行 tag 是**场景域**目录（category_path 源自「场景标签」树，
-- 如「内置标签 / 动态实体 / 道路使用者类型 / 机动车」）。切到 FRAGMENT 后这些行
-- 不再由任何域供给，属历史脏数据；且它们不出现在 scope=FRAGMENT 的拉取结果里，
-- 同步只做 upsert、不删除，因此会一直留在本地标签库并出现在标签管理页。
--
-- 清理前先确认引用量（data_slice_tag 外键为 ON DELETE CASCADE，
-- 删 tag 会连带删除切片上的打标记录）：
--   SELECT count(*) AS refs
--     FROM data_slice_tag dst JOIN tag g ON g.id = dst.tag_id
--    WHERE g.scope IS NULL;
--
-- ⚠️ scope IS NULL 同时也涵盖「DIMS 标签管理页手工新建」的标签（create 接口未写 scope），
--    按域隔离的语义这些行同样应归入 FRAGMENT；若仍要保留，先执行回填再清理：
--   UPDATE tag SET scope = 'FRAGMENT' WHERE scope IS NULL;   -- 保留式：整表归一域
--   DELETE FROM tag WHERE scope IS NULL;                      -- 清理式：仅删历史场景域目录
--    两条互斥，二选一。
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_tag_scope ON tag(scope);
