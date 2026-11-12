package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.isim.dims.entity.GpsTrajectory;
import com.isim.dims.mapper.GpsTrajectoryMapper;
import com.isim.dims.service.GpsTrajectoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * GPS 轨迹点 Service 实现
 *
 * 仅提供 MyBatis-Plus IService 的默认 CRUD + saveBatch 批量能力。
 * 具体业务查询在 TrajectoryServiceImpl 中直接使用 Mapper。
 * @feature: trajectory
 */
@Slf4j
@Service
public class GpsTrajectoryServiceImpl
        extends ServiceImpl<GpsTrajectoryMapper, GpsTrajectory>
        implements GpsTrajectoryService {
}
