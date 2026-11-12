-- ============================================================
-- DIMS DDL Migration（PostgreSQL 方言）
-- Version: V25
-- Description: 用户个性化配置改为每用户多套方案（增加方案名称/描述，唯一约束升级为 (user_id, name)）
-- ============================================================
-- 背景：原模型为「每用户一条」(UNIQUE(user_id))。现需支持「每用户多套方案」：
--   每个用户可创建多套个性化配置方案，方案名在单用户内唯一，
--   查询按 (user_id, config_id) 定位、列表按 user_id 分页。
--
-- 字段说明：
--   name        方案名称，人类可读（支持中文），NOT NULL，单用户内唯一。
--               VARCHAR(120)：短标签而非自由文本，120 足以容纳中文约 40 字 + 余量；
--               该列作为唯一约束第二列参与索引，过长会膨胀索引。
--   description 方案描述，可选（NULL 表示无描述）。VARCHAR(500)。
--
-- 加列顺序：name 用 NOT NULL DEFAULT 一步完成——PG 11+ 对带非空默认值的加列是
--   元数据级操作，空表与有行表均不报错；该默认值同时为存量「每用户一条」旧行
--   给出语义合理的方案名（default）。旧约束已保证每用户至多 1 行，回填后仍为
--   1 行/用户，不与新复合唯一约束冲突。description 可空，直接加列即可。
--
-- 索引说明：新唯一约束 UNIQUE(user_id, name) 自带 B-tree 索引，前导列为 user_id。
--   列表查询 WHERE user_id = ? 与归属查询 WHERE id = ? AND user_id = ? 均可命中该索引，
--   因此无需额外建 idx_user_dashboard_config_user_id。
--   约束名带表前缀，满足 PG 索引名 schema 全局唯一的要求。
--
-- 无外键：user_id 属外部 IAMS 用户体系，DIMS 本地无用户表，不做本地 FK。
--
-- 注意：本项目无 Flyway 自动执行机制，本脚本需手动 apply。无 down/回滚脚本。
-- ============================================================

-- 1. 加方案名称列（一步到位 NOT NULL + 默认回填，空表/有行表均安全）
ALTER TABLE user_dashboard_config
    ADD COLUMN IF NOT EXISTS name VARCHAR(120) NOT NULL DEFAULT 'default';

-- 2. 加方案描述列（可空，无默认值）
ALTER TABLE user_dashboard_config
    ADD COLUMN IF NOT EXISTS description VARCHAR(500) NULL;

-- 3. 卸下旧唯一约束（「每用户一条」语义作废）
ALTER TABLE user_dashboard_config
    DROP CONSTRAINT IF EXISTS uk_user_dashboard_config_user;

-- 4. 升级为复合唯一约束（前导列 user_id 顺带覆盖列表查询）
ALTER TABLE user_dashboard_config
    ADD CONSTRAINT uk_user_dashboard_config_user_name UNIQUE (user_id, name);
