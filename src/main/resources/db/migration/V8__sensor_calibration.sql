-- ============================================================
-- DIMS DDL Migration
-- Version: V8
-- Description: 传感器安装标定表（通用，2D Replay 一期）
-- ============================================================
-- 背景：docs/REPLAY-2D-DESIGN.md §7.3 / DEVELOPMENT.md §6.3。
--   记录相机/雷达/超声波/IMU/LiDAR 的安装位姿（ego 系），
--   用于车体俯视图传感器点位渲染与障碍物 ego 系换算。
--   相机点位读取优先级：sensor_calibration → camera_calibration.vehicle_xyz → 默认布局。
--
-- pose_json 为 JSON 对象字符串，如 {"x":0.0,"y":0.0,"z":1.2,"yaw":0.0,"pitch":0.0,"roll":0.0}
--   x/y/z 单位 m；yaw/pitch/roll 单位 rad；frame=ego（原点=车体后轴中心，x 前 y 左 z 上）
-- source: yaml|manual（标定来源，仅记录）
-- ============================================================

CREATE TABLE sensor_calibration (
    id           VARCHAR(36)  PRIMARY KEY,
    slice_id     VARCHAR(36)  NOT NULL,
    sensor_name  VARCHAR(100) NOT NULL, -- 相机用位置名(front_wide等)，雷达用 topic 段名(result_front等)
    sensor_type  VARCHAR(30)  NOT NULL, -- camera|radar|ultrasonic|imu|lidar
    frame        VARCHAR(20)  NOT NULL DEFAULT 'ego', -- 坐标系：ego（原点=车体后轴中心）
    unit         VARCHAR(10)  NOT NULL DEFAULT 'm', -- 长度单位：m
    pose_json    JSONB         NOT NULL, -- {x,y,z,yaw,pitch,roll}，yaw/pitch/roll unit: rad
    source       VARCHAR(20)  DEFAULT 'manual', -- yaml|manual
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (slice_id) REFERENCES data_slice(id) ON DELETE CASCADE,
    CONSTRAINT uk_slice_sensor UNIQUE (slice_id, sensor_name)
);

