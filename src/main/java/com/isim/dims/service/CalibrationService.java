package com.isim.dims.service;

import com.isim.dims.dto.SensorCalibrationRequest;
import com.isim.dims.dto.SensorCalibrationResponse;
import com.isim.dims.dto.SensorCalibrationSet;

/**
 * 传感器标定 Service
 *
 * <p>职责（契约 v1.3.0 / DESIGN §5.2）：
 * <ul>
 *   <li>GET：返回切片传感器安装标定与车体尺寸；无标定返回 has_calibration=false（不报错），
 *       相机点位回退 camera_calibration.vehicle_xyz</li>
 *   <li>POST：按 (slice_id, sensor_name) upsert 摄入单条标定（yaw/pitch/roll unit: rad）</li>
 * </ul>
 * 异常约定（message 前缀由 Controller 映射）：SLICE_NOT_FOUND / CALIBRATION_INVALID。
 * @feature: calibration
 */
public interface CalibrationService {

    /**
     * 查询切片传感器标定集合
     *
     * @param sliceId 切片 UUID
     * @return 标定集合（frame=ego, unit=m；vehicle 无标定时为默认值 4.8/1.9/2.8）
     */
    SensorCalibrationSet getCalibrationSet(String sliceId);

    /**
     * 摄入单条传感器标定（同名 upsert）
     *
     * @param sliceId 切片 UUID
     * @param request 标定请求
     * @return 落库记录（201）
     */
    SensorCalibrationResponse createCalibration(String sliceId, SensorCalibrationRequest request);
}
