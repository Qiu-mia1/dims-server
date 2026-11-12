-- ============================================================
-- DIMS Phase 2 DDL Migration
-- Version: V2
-- Description: GPS trajectory table
-- ============================================================

-- ============================================================
-- GPS 轨迹点（缓存）
-- ============================================================
CREATE TABLE IF NOT EXISTS gps_trajectory (
    id               VARCHAR(36)  PRIMARY KEY,
    slice_id         VARCHAR(36)  NOT NULL,
    ts               TIMESTAMP     NOT NULL, -- UTC RFC3339
    lat              DOUBLE PRECISION       NOT NULL, -- 纬度 WGS84, unit: degrees
    lon              DOUBLE PRECISION       NOT NULL, -- 经度 WGS84, unit: degrees
    altitude         DOUBLE PRECISION, -- 海拔, unit: m
    heading          DOUBLE PRECISION, -- 航向角, unit: degrees
    speed            DOUBLE PRECISION, -- 速度, unit: m/s
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_gps_trajectory_slice_id ON gps_trajectory (slice_id);
CREATE INDEX idx_ts ON gps_trajectory (ts);

