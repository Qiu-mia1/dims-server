-- ============================================================
-- DIMS DDL Migration
-- Version: V14
-- Description: 驾驶舱统计提取任务表 + 切片统计指标表（驾驶舱统计模块）
-- ============================================================
-- 背景：DEVELOPMENT.md §5.6 扩展（驾驶舱统计模块，Phase 1a）。
--   驾驶舱（Dashboard）需要展示切片级统计指标（车辆信息/采集里程/
--   车速/道路场景/功能状态/接管介入/工况数据），本迁移将
--   统计提取任务状态机与切片统计指标持久化到 MySQL。
--
-- dashboard_stats_task 状态机（与 scene_extraction_task 同构）：
--   PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试）
--
-- slice_stats 为 data_slice 的 1:1 统计结果表（slice_id UNIQUE），
-- task_id 指向最近一次 SUCCESS 提取任务（任务删除时置 NULL）。
-- 单位约定（宪法第 7 节）：里程 unit: km，车速 unit: m/s，
-- 时长 unit: s，曲率 unit: 1/m；时间字段一律 UTC。
--
-- 注意：MySQL 8.0 不支持 CREATE TABLE IF NOT EXISTS 之外的幂等写法，
-- 新表一次性创建，不涉及存量数据迁移。
-- ============================================================

CREATE TABLE dashboard_stats_task (
    id              VARCHAR(36)   PRIMARY KEY,
    slice_id        VARCHAR(36)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- PENDING|RUNNING|SUCCESS|FAILED
    progress        INT           DEFAULT 0, -- 进度 0-100
    error_msg       VARCHAR(2000), -- 错误信息（FAILED 时非空）
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMP(3), -- 任务结束时间 UTC（SUCCESS/FAILED 时写入）
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_dashboard_stats_task_slice_status ON dashboard_stats_task (slice_id, status);
CREATE INDEX idx_dashboard_stats_task_created_at ON dashboard_stats_task (created_at);

CREATE TABLE slice_stats (
    id                VARCHAR(36)  PRIMARY KEY,
    slice_id          VARCHAR(36)  NOT NULL UNIQUE,
    task_id           VARCHAR(36), -- 最近一次 SUCCESS 提取任务 id

    -- 车辆信息
    vehicle_type      VARCHAR(100), -- 车辆型号（/config_service/vpi vehicle_type）

    -- 采集里程（unit: km）
    vehicle_mileage_start  DOUBLE PRECISION, -- 起始里程, unit: km
    vehicle_mileage_end    DOUBLE PRECISION, -- 结束里程, unit: km
    vehicle_mileage_delta  DOUBLE PRECISION, -- 采集里程 = end-start, unit: km

    -- 车速（unit: m/s）
    speed_avg         DOUBLE PRECISION, -- 平均车速, unit: m/s
    speed_max         DOUBLE PRECISION, -- 最高车速, unit: m/s

    -- 道路场景类型
    road_scene        VARCHAR(32), -- 主要道路场景 URBAN|HIGHWAY|TUNNEL|TOLL_GATE|ROUNDABOUT
    road_scene_source VARCHAR(64), -- 场景来源: unp_planning|ddld|mff|majority_vote

    -- 功能状态快照
    noa_active        BOOLEAN DEFAULT FALSE, -- NOA 是否有激活段
    aeb_active        BOOLEAN DEFAULT FALSE, -- AEB 是否有触发
    apa_active        BOOLEAN DEFAULT FALSE, -- APA 是否有任务
    acc_active        BOOLEAN DEFAULT FALSE, -- ACC 是否有激活段
    lcc_active        BOOLEAN DEFAULT FALSE, -- LCC+ 是否有激活段

    -- 功能状态统计
    noa_activation_count    INT DEFAULT 0, -- NOA 激活次数
    noa_total_duration_s    DOUBLE PRECISION, -- NOA 总激活时长, unit: s
    aeb_trigger_count       INT DEFAULT 0, -- AEB 触发次数
    apa_task_count          INT DEFAULT 0, -- APA 泊车任务次数
    apa_success_count       INT DEFAULT 0, -- APA 泊车成功次数

    -- 接管/介入统计
    takeover_count    INT DEFAULT 0, -- 接管次数（driver_work_type 变迁）
    intervention_count INT DEFAULT 0, -- 介入次数（override 标志）

    -- 工况数据
    curvature_avg     DOUBLE PRECISION, -- 平均弯道曲率, unit: 1/m
    curvature_max     DOUBLE PRECISION, -- 最大弯道曲率, unit: 1/m
    gnss_sat_num_avg  DOUBLE PRECISION, -- 平均卫星数
    gnss_hdop_avg     DOUBLE PRECISION, -- 平均 HDOP
    lane_change_count_left  INT DEFAULT 0, -- 左变道次数
    lane_change_count_right INT DEFAULT 0, -- 右变道次数
    highway_mode      BOOLEAN, -- 高速优先是否开启

    -- 提取状态
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING|RUNNING|SUCCESS|FAILED
    error_msg         VARCHAR(2000), -- 错误信息

    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES dashboard_stats_task(id) ON DELETE SET NULL
);
CREATE INDEX idx_slice_stats_slice_id ON slice_stats (slice_id);
CREATE INDEX idx_status ON slice_stats (status);

