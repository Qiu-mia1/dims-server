package com.isim.dims.service;

import com.isim.dims.dto.ExtractRequest;
import com.isim.dims.dto.TrajectoryQueryResult;

import java.util.Map;

/**
 * GPS 轨迹 Service 接口
 *
 * 负责 GPS 轨迹数据的查询和异步提取。
 * 轨迹数据通过 Python extract_gps_trajectory.py 提取并缓存到 gps_trajectory 表。
 * @feature: trajectory
 */
public interface TrajectoryService {

    /**
     * 查询切片的 GPS 轨迹
     *
     * <p>三级判定：先查 gps_trajectory 缓存 → 空则查 sensor_stream 判断 GPS 能力
     * → 有 GPS 能力则校验 bag 文件并自动触发异步提取。
     * 结果通过 {@link TrajectoryQueryResult#getStatus()} 区分三种情况：</p>
     * <ul>
     *   <li>READY：有缓存轨迹，携带 TrajectoryResponse</li>
     *   <li>NOT_EXTRACTED：有 GPS sensor_stream 但未提取，已触发异步提取，携带 task_id</li>
     *   <li>NO_GPS_DATA：无 GPS sensor_stream，该切片确认为空</li>
     *   <li>IMPORT_NOT_READY：导入流水线未完成，暂无法下结论</li>
     *   <li>BAG_FILE_MISSING：有 GPS 流但 bag 文件缺失</li>
     * </ul>
     *
     * @param sliceId 切片 UUID
     * @return 轨迹查询结果
     * @throws RuntimeException 如果切片不存在
     */
    TrajectoryQueryResult getTrajectory(String sliceId);

    /**
     * 异步提取 GPS 轨迹
     *
     * @param sliceId 切片 UUID
     * @param request 提取请求
     * @return 包含 task_id 和 status 的 Map
     * @throws RuntimeException 如果切片不存在
     */
    Map<String, String> extractTrajectory(String sliceId, ExtractRequest request);

    /**
     * 查询轨迹提取任务进度
     *
     * @param taskId 任务 UUID
     * @return 任务详情 Map（含 task_id, slice_id, status, progress, error_msg）
     */
    Map<String, Object> getTrajectoryTask(String taskId);
}
