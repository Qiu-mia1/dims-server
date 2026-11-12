-- ============================================================================
-- video_clip_asset 视频片段（Clip）级 embedding 向量表（PostgreSQL + pgvector）
--
-- !!! 此脚本在【 pgvector 独立向量库】执行，不在 DIMS 主库执行 !!!
--   host:     10.130.212.2
--   port:     15432
--   database: dims_pgvector
--   user:     isim
--
-- 执行方式示例：
--   psql "host=10.130.212.2 port=15432 dbname=dims_pgvector user=isim" \
--        -f video_clip_asset.sql
--
-- 用途：
--   以文搜图 / 以图搜图 的「片段级召回」分支。与 image_asset（帧级）构成双路召回：
--     MP4 → FFmpeg → Frame(每 5s 帧图) + Clip(时间片段) → SigLIP2 → 768D
--          → image_asset HNSW + video_clip_asset HNSW → Top 100 候选池
--          → 元数据过滤 + 时间连续性打分 → Reranker → Top 20（视频 + 时间区间 + 帧）
--
--   写入端：视频提取流水线对同一 topic 的连续帧按时间窗聚合成片段后，
--           取片段代表帧（或帧特征均值）计算 embedding 落库。
--   检索端：VideoClipAssetMapper（@DS("pgvector") 切换到该动态数据源）
--
-- 说明：本表未创建时，检索端会自动降级为「仅帧级召回」，不影响接口可用性。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- 以 slice_id + topic_safe + 时间窗唯一，便于写入端幂等 upsert
CREATE TABLE IF NOT EXISTS video_clip_asset (
    id              BIGSERIAL PRIMARY KEY,
    slice_id        VARCHAR(64)  NOT NULL,
    topic           VARCHAR(255),
    topic_safe      VARCHAR(255) NOT NULL,
    start_ns        BIGINT       NOT NULL,             -- 片段起始 bag 纳秒时间戳（含）
    end_ns          BIGINT       NOT NULL,             -- 片段结束 bag 纳秒时间戳（含）
    frame_count     INTEGER      DEFAULT 0,            -- 片段内帧图数量
    clip_file       TEXT,                              -- 片段产物文件名（可选）
    embedding_model VARCHAR(128),                      -- 如 siglip2
    embedding       vector(768),                       -- SigLIP2 768 维特征
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_video_clip_asset UNIQUE (slice_id, topic_safe, start_ns, end_ns)
);

-- HNSW 向量索引（余弦），加速 Top-N 近似最近邻检索
CREATE INDEX IF NOT EXISTS idx_video_clip_asset_embedding
    ON video_clip_asset
    USING hnsw (embedding vector_cosine_ops);

-- slice_id 过滤辅助索引
CREATE INDEX IF NOT EXISTS idx_video_clip_asset_slice
    ON video_clip_asset (slice_id);

-- 时间区间过滤辅助索引（start_ns / end_ns 重叠判断）
CREATE INDEX IF NOT EXISTS idx_video_clip_asset_range
    ON video_clip_asset (slice_id, topic_safe, start_ns, end_ns);
