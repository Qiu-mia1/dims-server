-- ============================================================
-- DIMS DDL Migration
-- Version: V27
-- Description: MXF 埋点数据包导入 + 对象存储架构
-- ============================================================
-- 背景：MXF 埋点数据开放接口（MXF埋点数据开放接口定义_0911.xlsx）
--   描述了 5 类共 54 张表（车辆运行/智驾运行/故障诊断/ADAS/AEB）的
--   JSON 日志字段。源数据按 .tar.gz 压缩包组织：
--       exp_data_packet_<VIN_md5后8>_<usTimestamp>.tar.gz
--         ├── meta                  ← UTF-8 文本：JSON 或 key=value，含 event_name / vin /
--         │                            car_type / project / collect_type / trigger_timestamp_us /
--         │                            utc_diff_us 等元信息
--         └── exp_data_packet_<VIN_md5后8>_<usTimestamp>.data
--                                     ← UTF-8 行分隔日志：每行格式
--                                       [YYYY-MM-DD HH:MM:SS.nanoseconds]:[SECTION]:{...json...}
--   本次需求新增 1 张主表：
--       mxf_packet          一行 = 一个 .tar.gz 数据包，存 meta + 解析摘要
--                              + 对象存储 key + 聚合快照
--
-- 设计要点：
--   1. 与 data_slice 完全解耦：MXF 数据不进 data_slice、不进 vin_md5_mapping，
--      不走 CFDI 流水线，仅用作"看板"聚合统计来源。
--   2. mxf_packet.section_stats 用 JSONB 存储导入时一次性解析的聚合快照
--      （54 个 section 的计数 + AEB/APA/AVP/距离/时长），看板直接汇总，
--      避免每次查询都要重读整包日志。
--   3. event_name 直接复制 meta 中的字符串，不做 FK 约束（meta 文件可能
--      写入尚未在 camera_event_config.json 注册的新事件）。
--   4. mxf_packet.special_event_count 字段冗余存储 8 类特殊事件标记，
--      便于看板过滤，避免每次都要 JOIN camera_event_config.json 解析。
--   5. 日志文件不再逐行入库，整体存对象存储（MinIO），
--      仅 mxf_packet.data_object_key 记录存储地址。
-- ============================================================

-- ============================================================
-- MXF 数据包主表：每个 .tar.gz 一条记录
-- ============================================================
CREATE TABLE mxf_packet (
    id                   VARCHAR(36)  PRIMARY KEY,            -- UUID
    file_name            VARCHAR(255) NOT NULL,                -- 原始文件名 exp_data_packet_xxxxxxx_xxxxxxxxxxxxxxx.tar.gz
    file_md5             VARCHAR(64)  NOT NULL,                -- 压缩包 MD5（32 hex）
    file_size            BIGINT       NOT NULL DEFAULT 0,      -- 压缩包字节数
    vin                  VARCHAR(32),                          -- VIN 快照（meta.vin）
    vin_md5              VARCHAR(64),                          -- meta 中 vin_md5（vin_md5_mapping.md5）
    event_name           VARCHAR(64),                          -- meta.event_name（事件类型）
    car_type             VARCHAR(64),                          -- meta.car_type
    project              VARCHAR(64),                          -- meta.project
    collect_type         VARCHAR(32),                          -- meta.collect_type
    trigger_timestamp_us BIGINT,                              -- meta.trigger_timestamp_us（数据侧本地时钟）
    utc_diff_us          BIGINT,                              -- meta.utc_diff_us（本地 → UTC 偏移）
    trigger_time         TIMESTAMP,                            -- 转换后的 UTC 触发时间
    packet_start         TIMESTAMP,                            -- 日志首行时间（UTC）
    packet_end           TIMESTAMP,                            -- 日志末行时间（UTC）
    record_count         INT          NOT NULL DEFAULT 0,      -- 解析出的日志行数
    section_count        INT          NOT NULL DEFAULT 0,      -- 独立 SECTION 数
    special_event_count  INT          NOT NULL DEFAULT 0,      -- 属于 8 类特殊事件的记录数
    status               VARCHAR(16)  NOT NULL DEFAULT 'PARSED',-- PARSED|FAILED
    error_msg            VARCHAR(2048),                        -- 解析失败原因（成功时 NULL）
    source               VARCHAR(32),                          -- 调用方 source（MMT/ZYT/Hirain/手工导入 等）
    data_object_key      TEXT,                                -- 日志文件在对象存储(MinIO)中的 key，如 mxf/packets/<id>/data.log
    data_size_bytes      BIGINT,                              -- 日志文件解压后字节数
    section_stats        JSONB,                                -- 导入时解析的聚合快照: {sections:{...}, aebCount, apaCount, avpCount, distanceKm, durationHours}
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mxf_packet_md5 UNIQUE (file_md5)             -- 同一压缩包不重复导入
);

CREATE INDEX idx_mxf_packet_vin        ON mxf_packet (vin);
CREATE INDEX idx_mxf_packet_event_name ON mxf_packet (event_name);
CREATE INDEX idx_mxf_packet_trigger    ON mxf_packet (trigger_time);
CREATE INDEX idx_mxf_packet_status     ON mxf_packet (status);

COMMENT ON COLUMN mxf_packet.data_object_key IS '日志文件在对象存储(MinIO)中的 key，如 mxf/packets/<id>/data.log';
COMMENT ON COLUMN mxf_packet.data_size_bytes IS '日志文件解压后字节数';
COMMENT ON COLUMN mxf_packet.section_stats   IS '导入时解析的聚合快照: {sections:{...}, aebCount, apaCount, avpCount, distanceKm, durationHours}';