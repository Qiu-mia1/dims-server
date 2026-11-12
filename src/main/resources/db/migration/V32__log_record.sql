-- ============================================================
-- DIMS DDL Migration（PostgreSQL 方言）
-- Version: V32
-- Description: 新增 log_record 表（forever_log / bsw_log 等日志包记录，一行 = 一个日志目录）
-- ============================================================
-- 注意：本项目无 Flyway 自动执行机制，本脚本需手动 apply。
-- ============================================================
-- 背景：mmt-ads 源目录下除 MXF 数据包外，还存在以 `_log` 结尾的独立日志文件，例如
--   {bosPrefix}/{yyyyMMdd}/{vin_md5}/{sessionDir}/public/<usTimestamp>_forever_log_<size>_<vin_md5>
-- 这类文件不参与切片导入（data_slice），但需要在「日志浏览」页面上可视化管理：
--   1. 元数据自动来源于同会话目录下的 meta 文件 + 对象 key 所在目录；
--   2. 支持按日志文件所在目录整体打包下载 zip。
--
-- 设计要点：
--   1. 一行 = 一个日志目录（dir_prefix 唯一）：同一目录下的多个 `_log` 文件只登记一条记录，
--      下载时按 dir_prefix 打包整个目录；调度重复扫描时按 dir_prefix upsert；
--   2. meta 字段（car_type / collect_type / project / vin ...）按 meta JSON 原样落列，
--      同时保留 meta_json 原文便于排查；meta 缺失时字段留空不影响列表展示；
--   3. bos_key 为该目录内的代表日志文件 key（字典序最小的 `_log` 文件），仅用于溯源展示；
--   4. file_count / file_size 为整个目录的对象数与总字节数，与打包下载内容一致；
--   5. 日志文件本体不入库、不落本地（meta-only 模式只登记元数据）。
-- ============================================================

CREATE TABLE IF NOT EXISTS log_record (
    id               VARCHAR(36)  PRIMARY KEY,                  -- UUID
    log_type         VARCHAR(64),                               -- forever_log / bsw_log / ...
    file_name        VARCHAR(512),                              -- 代表日志文件名（key 最后一段，用于展示）
    bos_key          VARCHAR(1024) NOT NULL,                    -- 代表日志文件在 BOS 中的完整 key
    dir_prefix       VARCHAR(1024) NOT NULL,                    -- 日志文件所在目录（一行一个目录，打包下载前缀）
    bucket           VARCHAR(128),                              -- 对象存储桶
    file_count       INTEGER,                                   -- 目录内对象数（含非 `_log` 文件）
    file_size        BIGINT,                                    -- 目录内对象总字节数
    last_modified    TIMESTAMP,                                 -- 目录内对象最后修改时间
    log_time         TIMESTAMP,                                 -- 文件名前缀微秒时间戳换算的 UTC 时间
    car_type         VARCHAR(64),                               -- meta.car_type
    sub_car_type     VARCHAR(64),                               -- meta.sub_car_type
    collect_type     VARCHAR(64),                               -- meta.collect_type
    country          VARCHAR(64),                               -- meta.country
    data_source_env  VARCHAR(32),                               -- meta.data_source_env
    dump_begin_us    BIGINT,                                    -- meta.dump_begin_us
    event_name       VARCHAR(128),                              -- meta.event_name
    fdi_type         VARCHAR(32),                               -- meta.fdi_type
    is_soca          BOOLEAN,                                   -- meta.is_soca
    maf_interface    VARCHAR(128),                              -- meta.maf_interface
    priority         VARCHAR(32),                               -- meta.priority
    project          VARCHAR(64),                               -- meta.project
    system_name      VARCHAR(256),                              -- meta.system_name
    use_anonymous_id BOOLEAN,                                   -- meta.use_anonymous_id
    utc_diff_us      BIGINT,                                    -- meta.utc_diff_us
    uuid             VARCHAR(64),                                -- meta.uuid
    vin              VARCHAR(64),                               -- meta.vin
    meta_key         VARCHAR(1024),                             -- 来源 meta 文件 key
    meta_json        TEXT,                                      -- meta 原文（JSON 或 key=value）
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_log_record_dir_prefix UNIQUE (dir_prefix)
);

CREATE INDEX IF NOT EXISTS idx_log_record_vin      ON log_record (vin);
CREATE INDEX IF NOT EXISTS idx_log_record_log_type ON log_record (log_type);
CREATE INDEX IF NOT EXISTS idx_log_record_event    ON log_record (event_name);
CREATE INDEX IF NOT EXISTS idx_log_record_time     ON log_record (log_time);
CREATE INDEX IF NOT EXISTS idx_log_record_bos_key  ON log_record (bos_key);

COMMENT ON TABLE  log_record              IS '日志包记录（forever_log / bsw_log 等，一行=一个日志目录，元数据来自 meta 文件）';
COMMENT ON COLUMN log_record.dir_prefix   IS '日志文件所在目录（唯一），打包下载时按此前缀列举对象';
COMMENT ON COLUMN log_record.bos_key      IS '目录内代表日志文件 key（字典序首个 `_log` 文件），仅用于溯源';
COMMENT ON COLUMN log_record.file_count   IS '目录内对象数，与打包下载的条目数一致';
COMMENT ON COLUMN log_record.file_size    IS '目录内对象总字节数，与打包下载体积一致';
COMMENT ON COLUMN log_record.log_time     IS '代表日志文件名前缀微秒时间戳换算的时间（UTC）';
COMMENT ON COLUMN log_record.meta_json    IS 'meta 文件原文，便于排查字段解析问题';
