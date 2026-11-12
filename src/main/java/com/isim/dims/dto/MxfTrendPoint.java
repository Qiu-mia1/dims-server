package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 趋势点（按天，北京时区；缺失日补 0，保证前端拿到定长序列）。
 *
 * <p>被左侧（VIN 视角）与中间（筛选器视角）两个面板共用，故提为顶层 DTO。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfTrendPoint {

    /** 轴标签，如 09-11 */
    private String label;

    /** 日期 yyyy-MM-dd */
    private String date;

    /** 次数（口径恒为「包数」，一个包算一次） */
    private long count;
}
