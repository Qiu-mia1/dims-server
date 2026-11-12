package com.isim.dims.service;

import com.isim.dims.dto.MxfDashboardSummaryResponse;
import com.isim.dims.dto.MxfOperationsResponse;

/**
 * MXF 看板聚合服务。
 *
 * <p>V27 起：明细不再逐行入库，本服务基于 {@code mxf_packet.section_stats}
 * 聚合快照汇总看板数据。
 *
 * @feature: mxf
 */
public interface MxfDashboardService {

    /**
     * 获取看板完整数据：KPI + 事件分布 + 特殊事件通知。
     *
     * @param timeRange         时间筛选范围：today|week|month|year|all（空值按 all）；
     *                          若传入 {@code timeStart}/{@code timeEnd} 则忽略本参数，按自定义范围统计
     * @param timeStart         自定义时间范围起点（RFC3339，可空表示不设下界）
     * @param timeEnd           自定义时间范围终点（RFC3339，可空表示不设上界）
     * @param specialAlertLimit 顶部特殊事件通知的最大条数
     * @return 看板响应
     */
    MxfDashboardSummaryResponse getSummary(String timeRange, String timeStart, String timeEnd,
                                           int specialAlertLimit);

    /**
     * 智驾运营数据看板（V1.1 重写版）汇总：辅助驾驶总里程/总时长、最长智驾距离（单次）、
     * 泊车使用次数、行车看板（10 类触发事件）、泊车看板（APA/RPA/AVP/LPNP/NSP/LRAS）。
     *
     * <p>数据来源：/mmt-ads/Momenta-MXF/LUS_CX835ICA_sop/ 目录下的 MXF 文件
     * （mxf_packet.section_stats 聚合快照）。</p>
     *
     * @param timeRange 时间筛选范围：today|week|month|year|all（空值按 all）；
     *                  若传入 {@code timeStart}/{@code timeEnd} 则忽略本参数，按自定义范围统计
     * @param timeStart 自定义时间范围起点（RFC3339，可空表示不设下界）
     * @param timeEnd   自定义时间范围终点（RFC3339，可空表示不设上界）
     * @return 智驾运营看板响应
     */
    MxfOperationsResponse getOperations(String timeRange, String timeStart, String timeEnd);
}
