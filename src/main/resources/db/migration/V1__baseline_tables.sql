-- ============================================================
-- DIMS Baseline DDL Migration（PostgreSQL 方言）
-- Version: V1
-- Description: 8 张基线表（原 MySQL 建表，按当前 schema 减去
--              V3/V6/V9/V17/V18 后加的列，完整回放迁移链）
--
-- 方言转换说明：
--   DATETIME → TIMESTAMP；TINYINT(1) → BOOLEAN；JSON → JSONB
--   ON UPDATE CURRENT_TIMESTAMP → 删除（应用层 MetaObjectHandler 填充）
--   表内 KEY → 外置 CREATE INDEX（PG 索引名 schema 全局唯一，已加表前缀）
--   列级 COMMENT → 行尾 -- 注释
-- ============================================================

-- ============================================================
-- 数据切片表（source_type 见 V3、trajectory_* 见 V6、
--   vin_md5_id/vin/import_md5/session_id/import_source_dir 见 V17、
--   utc_diff_us 见 V18）
-- ============================================================
CREATE TABLE data_slice (
    id              VARCHAR(36)   PRIMARY KEY,
    name            VARCHAR(255)  NOT NULL,
    file_path       VARCHAR(512)  NOT NULL, -- 原始 bag 文件路径
    file_size       BIGINT        NOT NULL, -- 文件字节数
    start_ts        TIMESTAMP,
    end_ts          TIMESTAMP,
    duration_ms     BIGINT,
    gps_min_lat     DOUBLE PRECISION, -- GPS 范围最小纬度
    gps_min_lon     DOUBLE PRECISION, -- GPS 范围最小经度
    gps_max_lat     DOUBLE PRECISION, -- GPS 范围最大纬度
    gps_max_lon     DOUBLE PRECISION, -- GPS 范围最大经度
    sensor_types    JSONB, -- ["camera","lidar","radar","gnss_imu","vehicle_signal","ultrasonic","adas","other"]
    msg_count_total BIGINT,
    status          VARCHAR(20)   NOT NULL DEFAULT 'UPLOADING',
    error_msg       TEXT, -- 错误信息
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP -- MySQL 为 ON UPDATE CURRENT_TIMESTAMP，PG 由应用层填充
);
CREATE INDEX idx_data_slice_status ON data_slice (status);
CREATE INDEX idx_data_slice_start_ts ON data_slice (start_ts);
CREATE INDEX idx_data_slice_created_at ON data_slice (created_at);

-- ============================================================
-- 导入任务表
-- ============================================================
CREATE TABLE ingestion_task (
    id         VARCHAR(36)  PRIMARY KEY,
    slice_id   VARCHAR(36)  NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING',
    progress   INT          DEFAULT 0,
    error_msg  TEXT,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 应用层填充
    CONSTRAINT ingestion_task_ibfk_1 FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_ingestion_task_slice_id ON ingestion_task (slice_id);

-- ============================================================
-- 导出任务表（updated_at 及查询路径索引见 V9）
-- ============================================================
CREATE TABLE export_task (
    id         VARCHAR(36)  PRIMARY KEY,
    slice_id   VARCHAR(36)  NOT NULL,
    start_ts   TIMESTAMP    NOT NULL,
    end_ts     TIMESTAMP    NOT NULL,
    topics     JSONB, -- 导出的 Topic 列表
    format     VARCHAR(20)  NOT NULL DEFAULT 'bag',
    file_path  VARCHAR(512),
    file_size  BIGINT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMP,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT export_task_ibfk_1 FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_export_task_status ON export_task (status);
CREATE INDEX idx_export_task_expires_at ON export_task (expires_at);

-- ============================================================
-- 标签表
-- ============================================================
CREATE TABLE tag (
    id          VARCHAR(36)  PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    category    VARCHAR(100), -- 分类路径
    description TEXT, -- 标签说明
    rule_expr   VARCHAR(500), -- 自动打标规则表达式
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tag_name UNIQUE (name)
);

-- ============================================================
-- 切片-标签关联表
-- ============================================================
CREATE TABLE data_slice_tag (
    id       VARCHAR(36) PRIMARY KEY,
    slice_id VARCHAR(36) NOT NULL,
    tag_id   VARCHAR(36) NOT NULL,
    source   VARCHAR(20) NOT NULL DEFAULT 'manual',
    CONSTRAINT uk_slice_tag UNIQUE (slice_id, tag_id),
    CONSTRAINT data_slice_tag_ibfk_1 FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE,
    CONSTRAINT data_slice_tag_ibfk_2 FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
);
CREATE INDEX idx_data_slice_tag_tag_id ON data_slice_tag (tag_id);

-- ============================================================
-- 传感器流表（duration_us/sensor_name 见 V3）
-- ============================================================
CREATE TABLE sensor_stream (
    id              VARCHAR(36)  PRIMARY KEY,
    slice_id        VARCHAR(36)  NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    msg_type        VARCHAR(255) NOT NULL,
    sensor_category VARCHAR(50)  NOT NULL,
    msg_count       INT          NOT NULL,
    frequency_hz    DOUBLE PRECISION, -- 平均频率 Hz
    first_ts        TIMESTAMP, -- 首条消息时间戳
    last_ts         TIMESTAMP, -- 末条消息时间戳
    metadata        JSONB, -- Topic 特定元数据
    CONSTRAINT sensor_stream_ibfk_1 FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_sensor_stream_slice_id ON sensor_stream (slice_id);
CREATE INDEX idx_sensor_stream_sensor_category ON sensor_stream (sensor_category);

-- ============================================================
-- 信号定义表（无 Java 实体引用，TINYINT(1) → BOOLEAN）
-- ============================================================
CREATE TABLE signal_definition (
    id           VARCHAR(36)  PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    display_name VARCHAR(200),
    category     VARCHAR(50)  NOT NULL,
    topic_pattern VARCHAR(255),
    data_type    VARCHAR(50),
    unit         VARCHAR(50),
    description  TEXT,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INT          DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 应用层填充
    CONSTRAINT uk_signal_definition_name UNIQUE (name)
); -- 信号定义表
CREATE INDEX idx_signal_definition_category ON signal_definition (category);
CREATE INDEX idx_signal_definition_is_active ON signal_definition (is_active);

-- ============================================================
-- 采集配置表（无 Java 实体引用，TINYINT(1) → BOOLEAN）
-- ============================================================
CREATE TABLE collection_config (
    id           VARCHAR(36) PRIMARY KEY,
    slice_id     VARCHAR(36),
    signal_id    VARCHAR(36) NOT NULL,
    sample_rate  INT,
    is_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    custom_params JSONB,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 应用层填充
    CONSTRAINT uk_slice_signal UNIQUE (slice_id, signal_id),
    CONSTRAINT collection_config_ibfk_1 FOREIGN KEY (signal_id) REFERENCES signal_definition(id) ON DELETE CASCADE,
    CONSTRAINT collection_config_ibfk_2 FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
); -- 采集配置表
CREATE INDEX idx_collection_config_slice_id ON collection_config (slice_id);
CREATE INDEX idx_collection_config_signal_id ON collection_config (signal_id);
