-- ============================================================
-- DIMS DDL Migration
-- Version: V28
-- Description: MXF 单车看板 v2 —— 积压车辆忽略名单
-- ============================================================
-- 背景（2026-09-17 需求方二次确认）：
--   「数据异常车辆」支持填写备注 → 填过备注的车辆不再计入异常统计与积压告警，
--   需要一张按 VIN 唯一的备注表（前端可查看与撤销）。
--
-- 2026-09-18 变更说明：
--   本脚本原含一段「事件改名 fushion_aeb → fusion_aeb」的 UPDATE。
--   需求方当日复核后**撤销该改名**，事件名保持 `fushion_aeb`（与平台侧
--   camera_event_config.json 的原始 key 一致），故改名段落已删除。
--   仅在文末保留一段「反向兜底」UPDATE，用于兼容改名期间可能已导入数据的环境。
--
-- ⚠️ Flyway 在本项目不自动执行，需手工 apply（见 docs/MXF-VEHICLE-DASHBOARD-PLAN.md §T5）。
-- ============================================================

-- ============================================================
-- 积压车辆忽略名单
-- ============================================================
CREATE TABLE IF NOT EXISTS mxf_offline_note (
    id         VARCHAR(36)  PRIMARY KEY,                       -- UUID
    vin        VARCHAR(64)  NOT NULL,                          -- mxf_packet.vin 原值（32 位 md5）
    note       VARCHAR(500) NOT NULL,                          -- 用户填写的备注
    created_by VARCHAR(64),                                    -- 填写人（JWT subject）
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mxf_offline_note_vin UNIQUE (vin)             -- 一个车辆标识一条备注，重复提交即覆盖
);

CREATE INDEX IF NOT EXISTS idx_mxf_offline_note_updated ON mxf_offline_note (updated_at);

COMMENT ON TABLE  mxf_offline_note            IS '单车 MXF 看板：积压车辆忽略名单（填过备注即不再计入异常统计）';
COMMENT ON COLUMN mxf_offline_note.vin        IS '车辆标识（mxf_packet.vin 原值，32 位 md5），唯一';
COMMENT ON COLUMN mxf_offline_note.note       IS '用户填写的备注内容（≤500 字符）';
COMMENT ON COLUMN mxf_offline_note.created_by IS '填写人（JWT subject）';
