package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.GpsTrajectory;
import org.apache.ibatis.annotations.Mapper;

/**
 * GPS 轨迹点 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 gps_trajectory 表的 CRUD。
 * 按切片查询在 Service 层通过 LambdaQueryWrapper 实现。
 * @feature: trajectory
 */
@Mapper
public interface GpsTrajectoryMapper extends BaseMapper<GpsTrajectory> {
}
