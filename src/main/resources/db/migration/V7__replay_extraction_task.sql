-- ============================================================
-- DIMS DDL Migration
-- Version: V7
-- Description: 回放提取任务表（2D Replay 一期）
-- ============================================================
-- 背景：docs/REPLAY-2D-DESIGN.md §7.2 / DEVELOPMENT.md §6.3。
--   障碍物/Raw 消息「按需提取 + 文件缓存」，消息内容不入库；
--   本表只记录任务状态机与产物路径，供前端轮询与缓存定位。
--
-- status 状态机（与通用 TaskStatus 语义不同，勿混用）：
--   PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次）
-- topics 为 JSON 数组字符串，如 ["/perception/radar/result_front"]
-- output_path 为产物根目录（replay 缓存目录下），如 {cache}/{slice_id}/replay/obstacles
--
-- 注意：MySQL 8.0 不支持 CREATE TABLE IF NOT EXISTS 之外的幂等写法，
-- 新表一次性创建，不涉及存量数据迁移。
-- ============================================================

CREATE TABLE replay_extraction_task (
    id          VARCHAR(36)  PRIMARY KEY,
    slice_id    VARCHAR(36)  NOT NULL,
    type        VARCHAR(20)  NOT NULL, -- obstacles|messages
    topics      JSONB, -- 目标 topic 列表
    start_ts    TIMESTAMP(3), -- 提取窗口起点 UTC（bag 时间戳，毫秒精度）
    end_ts      TIMESTAMP(3), -- 提取窗口终点 UTC（bag 时间戳，毫秒精度）
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING|RUNNING|SUCCESS|FAILED
    progress    INT          DEFAULT 0, -- 进度 0-100
    output_path VARCHAR(512), -- 产物根目录（replay 缓存目录下）
    message     TEXT, -- 错误信息/摘要
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP(3), -- 任务结束时间 UTC（SUCCESS/FAILED 时写入）
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_replay_extraction_task_slice_status ON replay_extraction_task (slice_id, status);
CREATE INDEX idx_replay_extraction_task_created_at ON replay_extraction_task (created_at);

