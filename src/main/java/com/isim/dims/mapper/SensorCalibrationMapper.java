package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.SensorCalibration;
import org.apache.ibatis.annotations.Mapper;

/**
 * 传感器标定 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 sensor_calibration 表的完整 CRUD。
 * (slice_id, sensor_name) upsert 通过 selectOne + insert/update 组合实现。
 * @feature: calibration
 */
@Mapper
public interface SensorCalibrationMapper extends BaseMapper<SensorCalibration> {
}
