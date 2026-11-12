package com.isim.dims.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.isim.dims.entity.GpsTrajectory;

/**
 * GPS 轨迹点 Service 接口
 *
 * 提供 MyBatis-Plus IService 默认实现（含 saveBatch 批量插入）。
 * 具体业务查询在 TrajectoryService 中实现。
 * @feature: trajectory
 */
public interface GpsTrajectoryService extends IService<GpsTrajectory> {
}
