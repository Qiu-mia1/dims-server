-- ============================================================================
-- image_asset 帧图 embedding 向量表（PostgreSQL + pgvector）
--
-- !!! 此脚本在【 pgvector 独立向量库】执行，不在 DIMS 主库执行 !!!
--   host:     10.130.212.2
--   port:     15432
--   database: dims_pgvector
--   user:     isim
--
-- 执行方式示例：
--   psql "host=10.130.212.2 port=15432 dbname=dims_pgvector user=isim" \
--        -f image_asset.sql
--
-- 用途：
--   以文搜图 / 以图搜图 / 特征搜图的 768 维 embedding 存储与检索。
--   写入端：dims-python/extract_video_segment.py（经 Java 注入 pgvector 连接参数）
--   检索端：ImageAssetMapper（@DS("pgvector") 切换到该动态数据源）
-- ============================================================================

-- 幂等启用 pgvector 扩展（pgvector/pgvector 官方镜像已内置，普通 PG 需先安装）
CREATE EXTENSION IF NOT EXISTS vector;

-- 以 slice_id + topic_safe + file_name 唯一，便于 extract_video_segment.py 幂等 upsert
CREATE TABLE IF NOT EXISTS image_asset (
    id              BIGSERIAL PRIMARY KEY,
    slice_id        VARCHAR(64)  NOT NULL,
    topic           VARCHAR(255),
    topic_safe      VARCHAR(255) NOT NULL,
    file_name       VARCHAR(512) NOT NULL,
    frame_ns        BIGINT,                         -- 帧图对应的 bag 纳秒时间戳
    object_key      TEXT,                           -- 存储 key：images/{slice_id}/{topic_safe}/{file_name}
    embedding_model VARCHAR(128),                   -- 如 siglip2
    embedding       vector(768),                    -- SigLIP2 768 维特征
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_image_asset UNIQUE (slice_id, topic_safe, file_name)
);

-- HNSW 向量索引（余弦），加速 Top-N 近似最近邻检索
CREATE INDEX IF NOT EXISTS idx_image_asset_embedding
    ON image_asset
    USING hnsw (embedding vector_cosine_ops);

-- slice_id 过滤辅助索引
CREATE INDEX IF NOT EXISTS idx_image_asset_slice
    ON image_asset (slice_id);