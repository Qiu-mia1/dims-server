package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 回放提取任务请求
 *
 * 对应 OpenAPI schema: ReplayExtractionRequest。
 * required: [type]
 * @feature: replay
 */
@Data
public class ReplayExtractionRequest {

    /** obstacles | messages | perception | map（ReplayExtractionType 枚举） */
    private String type;

    /**
     * topic 列表：type=messages 时必填（可多个）；其余类型可选，为空取默认：
     * obstacles → 全部雷达/超声波 topic（/perception/radar/*、/sensor/ultrasonic/*）；
     * perception → /momenta_public/perception/*（smooth_object 融合感知）；
     * map → /momenta_public/ddld/*（landmark_vis ddld 地图）
     */
    private List<String> topics;

    /** 提取窗口起点（bag 时间戳），默认切片 start_ts，UTC RFC3339 */
    private String startTs;

    /** 提取窗口终点（bag 时间戳），默认切片 end_ts，UTC RFC3339 */
    private String endTs;
}
