package com.isim.dims.dto;

import lombok.Data;

/**
 * GPS 轨迹查询结果
 *
 * <p>用于区分三种查询结果，由 Controller 映射为不同 HTTP 状态码：</p>
 * <ul>
 *   <li>READY            → 200 + TrajectoryResponse（gps_trajectory 有缓存）</li>
 *   <li>NOT_EXTRACTED    → 202 + task_id（有 GPS sensor_stream 但轨迹未提取，已触发异步提取）</li>
 *   <li>NO_GPS_DATA      → 404 + TRAJECTORY_NO_GPS_DATA（无 GPS sensor_stream，已确认为空）</li>
 *   <li>IMPORT_NOT_READY → 409 + TRAJECTORY_IMPORT_NOT_READY（导入未完成，无法下结论）</li>
 *   <li>BAG_FILE_MISSING → 409 + TRAJECTORY_BAG_FILE_MISSING（有 GPS 流但 bag 文件缺失）</li>
 * </ul>
 * @feature: trajectory
 */
@Data
public class TrajectoryQueryResult {

    /** READY | NOT_EXTRACTED | NO_GPS_DATA | IMPORT_NOT_READY | BAG_FILE_MISSING */
    private String status;

    /** status=READY 时携带的轨迹数据 */
    private TrajectoryResponse trajectory;

    /** status=NOT_EXTRACTED 时携带的提取任务 ID（已触发或进行中） */
    private String taskId;

    /** 辅助说明（含导入任务状态等上下文信息） */
    private String message;
}
