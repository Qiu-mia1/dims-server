-- ============================================================
-- DIMS CFDI DDL Migration
-- Version: V3
-- Description: CFDI data support: camera_calibration, cfdi_meta tables,
--              source_type on data_slice, duration_us on sensor_stream
-- ============================================================

-- ============================================================
-- 摄像头标定参数表（CFDI 专用）
-- ============================================================
CREATE TABLE IF NOT EXISTS camera_calibration (
    id                    VARCHAR(36)  PRIMARY KEY,
    slice_id              VARCHAR(36)  NOT NULL,
    sensor_name           VARCHAR(100) NOT NULL,
    serial_number         VARCHAR(100),
    sensor_type           VARCHAR(50),
    camera_model          VARCHAR(50),
    timestamp_shift       DOUBLE PRECISION,
    vehicle_xyz           JSONB, -- 车辆坐标系安装位置 [x,y,z]
    r_s2b                 JSONB, -- 旋转向量 [rx,ry,rz] rad
    t_s2b                 JSONB, -- 平移向量 [tx,ty,tz] m
    fx                    DOUBLE PRECISION, -- 焦距 x, unit: pixel
    fy                    DOUBLE PRECISION, -- 焦距 y, unit: pixel
    cx                    DOUBLE PRECISION, -- 主点偏移 x, unit: pixel
    cy                    DOUBLE PRECISION, -- 主点偏移 y, unit: pixel
    kc2                   DOUBLE PRECISION, -- 径向畸变系数 k2
    kc3                   DOUBLE PRECISION, -- 径向畸变系数 k3
    kc4                   DOUBLE PRECISION, -- 径向畸变系数 k4
    kc5                   DOUBLE PRECISION, -- 径向畸变系数 k5
    is_fisheye            SMALLINT   DEFAULT 0, -- 是否为鱼眼摄像头
    line_exposure_delay   DOUBLE PRECISION, -- 行曝光延迟
    width                 INT, -- 图像宽度, unit: pixel
    height                INT, -- 图像高度, unit: pixel
    suggested_rect_region JSONB, -- 建议裁切区域
    calib_version         VARCHAR(100), -- 标定版本
    calib_details         TEXT, -- 标定详情
    calib_date            VARCHAR(50), -- 标定日期
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_camera_calibration_slice_id ON camera_calibration (slice_id);

-- ============================================================
-- CFDI 元数据表
-- ============================================================
CREATE TABLE IF NOT EXISTS cfdi_meta (
    id                  VARCHAR(36)  PRIMARY KEY,
    slice_id            VARCHAR(36)  NOT NULL,
    car_type            VARCHAR(50), -- 车型
    project             VARCHAR(50), -- 项目名称
    event_name          VARCHAR(100), -- 事件名称
    vin                 VARCHAR(50), -- 车辆VIN码
    collect_type        VARCHAR(30), -- 采集类型
    data_source_env     VARCHAR(10), -- 数据源环境
    meta_data           JSONB, -- 元数据字典
    filter_meta_data    JSONB, -- 过滤元数据
    log_info_data       JSONB, -- 日志信息
    upload_done_data    JSONB, -- 上传完成标记
    good_flag           VARCHAR(20), -- 数据质量标记
    dump_finish         VARCHAR(20), -- dump完成标记
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE
);
CREATE INDEX idx_cfdi_meta_slice_id ON cfdi_meta (slice_id);
CREATE INDEX idx_car_type ON cfdi_meta (car_type);
CREATE INDEX idx_project ON cfdi_meta (project);
CREATE INDEX idx_event_name ON cfdi_meta (event_name);

-- ============================================================
-- data_slice 表变更：新增数据来源类型字段
-- ============================================================
ALTER TABLE data_slice
    ADD COLUMN source_type VARCHAR(10) NOT NULL DEFAULT 'BAG'; -- 数据来源:BAG|CFDI

-- ============================================================
-- sensor_stream 表变更：新增传感器流时长字段
-- ============================================================
ALTER TABLE sensor_stream
    ADD COLUMN duration_us BIGINT; -- 传感器流时长(微秒), CFDI sub_span_us 分发

-- ============================================================
-- sensor_stream 表变更：新增传感器名字段及索引
-- ============================================================
ALTER TABLE sensor_stream
    ADD COLUMN sensor_name VARCHAR(100); -- 传感器名, CFDI传感器名精确匹配, 避免JSON LIKE全表扫描

CREATE INDEX idx_sensor_name ON sensor_stream(sensor_name);

