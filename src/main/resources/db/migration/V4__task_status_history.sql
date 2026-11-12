-- ============================================================
-- DIMS Phase 2 DDL Migration
-- Version: V4
-- Description: Ingestion task status history table
-- ============================================================

-- ============================================================
-- 导入任务状态变更历史表
-- 记录每次状态变更的时间戳，用于前端 StatusTimeline 渲染
-- ============================================================
CREATE TABLE IF NOT EXISTS ingestion_task_status_history (
    id               VARCHAR(36)  PRIMARY KEY,
    task_id          VARCHAR(36)  NOT NULL, -- 关联的导入任务 UUID
    status           VARCHAR(20)  NOT NULL, -- UPLOADING|CONVERTING|PARSING|TAGGING|READY|ERROR
    progress         INT          DEFAULT 0, -- 变更时的进度值
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES ingestion_task(id) ON DELETE CASCADE
);
CREATE INDEX idx_ingestion_task_status_history_task_id ON ingestion_task_status_history (task_id);

