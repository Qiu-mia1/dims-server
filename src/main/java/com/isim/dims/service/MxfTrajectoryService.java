package com.isim.dims.service;

import com.isim.dims.dto.MxfTrajectoryMonitorResponse;

/**
 * MXF 行驶轨迹监控服务
 *
 * <p>基于 MXF 包解析出的轨迹点（mxf_trajectory_point），提供行驶轨迹监控
 * 聚合视图：车辆列表 / 轨迹段（每个 MXF 包一段）/ 触发事件轨迹 / 里程与车速统计。
 *
 * @feature: mxf
 */
public interface MxfTrajectoryService {

    /**
     * 行驶轨迹监控聚合查询
     *
     * @param timeRange 预设窗口：today|week|month|year|all（北京时间自然日口径），
     *                  timeStart/timeEnd 同时给出时忽略
     * @param timeStart 自定义窗口起点；支持 RFC3339/ISO（带 Z/偏移）或北京时间
     *                  "yyyy-MM-dd[ HH:mm[:ss]]"
     * @param timeEnd   自定义窗口终点（格式同上）
     * @param vin       车辆筛选：真实 VIN 或 meta 中的 vin_md5
     * @return 聚合响应（summary + vehicles）
     */
    MxfTrajectoryMonitorResponse getMonitor(String timeRange, String timeStart, String timeEnd, String vin);
}
