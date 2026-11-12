package com.isim.dims.service;

import com.isim.dims.dto.VehicleTrackResponse;

/**
 * 看板「车辆位置地图」服务。
 *
 * <p>口径（需求）：以 VIN 为基础，每辆车取一条触发时间在最近 N 小时内的
 * data_slice 切片，输出该切片 /mla/egopose 的主车经纬度轨迹与最后位置。</p>
 *
 * <p>数据来源：切片回放产物 rosdata.json 的 {@code egoPoseMessages}
 * （extract_replay.py 从 /mla/egopose 提取，经纬度为度制 WGS-84）。</p>
 *
 * @feature: mxf
 */
public interface VehicleTrackService {

    /**
     * 查询最近 hours 小时内每辆车的定位轨迹。
     *
     * @param hours       时间窗口（小时，<=0 按默认 24；上限 168）
     * @param maxVehicles 最多返回车辆数（<=0 按默认 20；上限 50）
     * @return 车辆轨迹响应（无定位数据的车辆也会返回，points 为空）
     */
    VehicleTrackResponse getVehicleTracks(int hours, int maxVehicles);
}