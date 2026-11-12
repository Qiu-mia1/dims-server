package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.CameraCalibration;
import org.apache.ibatis.annotations.Mapper;

/**
 * 摄像头标定参数 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 camera_calibration 表的完整 CRUD。
 * @feature: calibration
 */
@Mapper
public interface CameraCalibrationMapper extends BaseMapper<CameraCalibration> {
}
