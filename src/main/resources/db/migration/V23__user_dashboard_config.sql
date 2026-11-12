-- ============================================================
-- DIMS DDL Migration（PostgreSQL 方言）
-- Version: V23
-- Description: 用户个性化配置表（数据回放看板，每用户一条）
-- ============================================================
-- 背景：数据回放看板个性化配置。不同角色（泊车 / 主动安全等）关注的数据项不同，
--   由前端组装配置 JSON，后端原样落库，不解析、不校验内容语义。
--   user_id 取自 IAMS JWT 的 sub claim（docs/子系统对接指南.md §3.1），
--   一个用户一行，保存即覆盖（upsert）。
--
-- config_json：前端组装的配置对象；JSONB 由 PG 保证合法；NOT NULL 表示
--   「空配置」由前端传 {}，避免 null 与 {} 两种「空」语义并存。
-- 查询路径：GET /v1/user-dashboard-configs/{userId}
-- 保存路径：PUT /v1/user-dashboard-configs/{userId}
-- 无外键：user_id 属外部 IAMS 用户体系，DIMS 本地无用户表，不做本地 FK。
--
-- 注意：本项目无 Flyway 自动执行机制，本脚本需手动 apply。
-- ============================================================

CREATE TABLE user_dashboard_config (
    id          VARCHAR(36) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL,                            -- IAMS 用户 UUID（JWT sub claim）
    config_json JSONB       NOT NULL,                            -- 前端组装的配置 JSON，后端不解析
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 创建时间，UTC
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 最近更新时间，UTC（写时由应用层填充）
    CONSTRAINT uk_user_dashboard_config_user UNIQUE (user_id)
);

-- 查询只有 user_id 一条路径，唯一约束已提供 B-tree 索引，无需额外 CREATE INDEX
