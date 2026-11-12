package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 可视化数据提取通用请求
 *
 * 对应视频段、GPS 轨迹、信号数据提取的请求体。
 * OpenAPI schemas: VideoExtractRequest, TrajectoryExtractRequest, SignalExtractRequest
 * @feature: visualization
 */
@Data
public class ExtractRequest {

    /** 要提取的 Topic 列表 */
    private List<String> topics;

    /** 时间窗口起点，默认切片 start_ts，UTC RFC3339 */
    private String startTs;

    /** 时间窗口终点，默认切片 end_ts，UTC RFC3339 */
    private String endTs;

    /** 每个信号最多返回点数（仅信号提取用），默认 10000 */
    private Integer maxPointsPerSignal;

    /** 触发类型（meta.event_name，可选）。视频提取时按 camera_event_config.json 收敛摄像头路 */
    private String eventName;
}
