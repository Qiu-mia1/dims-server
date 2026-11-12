package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单车看板 · 左下「数据异常车辆」面板响应。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/abnormal}。可选 {@code sourceType}
 * （MMT/ZYT/Hirain）：不传 = 全来源（向后兼容），传了按 {@code data_slice.source_type} 过滤。
 * 积压判定对每 VIN 的最新包做全量比对，<b>不受时间范围筛选影响</b>，
 * 但受顶部「项目」下拉影响（日报看板要求项目联动整页）。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfAbnormalPanelResponse {

    /** 本次快照生成时间（UTC） */
    private LocalDateTime generatedAt;

    /** 当前生效阈值 + 计数 */
    private Thresholds thresholds;

    /** 数据上传异常（积压）车辆，按积压天数倒序 */
    private List<AbnormalVin> abnormalVehicles;

    /** 已忽略（填过备注，不再计入异常统计）的车辆 */
    private List<IgnoredVin> ignoredVehicles;

    /**
     * 生效阈值（来自后端配置，前端只读展示）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Thresholds {
        /** 积压判定阈值（天）：最新包「上传时间 − 触发时间」超过该天数即判定为积压 */
        private int backlogDelayDays;
        /** 积压告警阈值（辆） */
        private int backlogVehicles;
        /** 当前异常（积压）车辆数 */
        private int abnormalCount;
        /** 异常车辆数是否已超过积压阈值 */
        private boolean backlogExceeded;
        /** 已忽略（填过备注）的车辆数 */
        private int ignoredCount;
    }

    /**
     * 数据上传异常（积压）车辆
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AbnormalVin {
        /** 车辆原始标识 */
        private String vin;
        /** 展示名 */
        private String display;
        /** 是否已反查到真实 VIN */
        private boolean resolved;
        /** 最后上传时间（created_at，UTC） */
        private LocalDateTime lastUploadAt;
        /** 最新包的有效触发时间（trigger_time → packet_start → created_at） */
        private LocalDateTime lastTriggerTime;
        /** 最新包的软件版本号（data_slice.system_name，可空） */
        private String systemName;
        /** 积压天数 = 上传时间 − 触发时间 */
        private long backlogDays;
    }

    /**
     * 已忽略（填过备注）的车辆。
     *
     * <p>用户为某辆车填写备注后，该车不再计入异常统计与积压阈值判断，改为在本列表中展示，
     * 支持查看备注与撤销（撤销后重新纳入统计）。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IgnoredVin {
        /** 车辆原始标识（mxf_packet.vin 原值，即 32 位 md5） */
        private String vin;
        /** 展示名（真实 VIN 或 md5 前 8 位） */
        private String display;
        /** 是否已反查到真实 VIN */
        private boolean resolved;
        /** 最后上传时间（created_at，UTC） */
        private LocalDateTime lastUploadAt;
        /** 用户填写的备注 */
        private String note;
        /** 备注更新时间（UTC） */
        private LocalDateTime updatedAt;
    }
}
