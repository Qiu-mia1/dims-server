package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单车看板 · 左上「单车视角」面板响应。
 *
 * <p>对应 {@code GET /v1/mxf/vehicle-dashboard/vin}。只包含该面板所需数据：
 * 车辆下拉选项 + 选中车辆 + 该车按天趋势 + 该车包级明细。</p>
 *
 * <p>明细粒度 = 包（V27 起 {@code mxf_event_record} 已删除），
 * 事件口径 = {@code mxf_packet.event_name}（包级单事件，一个包最多 1 次）。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfVinPanelResponse {

    /** 本次快照生成时间（UTC） */
    private LocalDateTime generatedAt;

    /** 本次趋势曲线的天数（前端展示与折线长度一致性校验用） */
    private int trendDays;

    /** 全部车辆（按最后上传时间倒序） */
    private List<VinOption> vinOptions;

    /** 当前选中的车辆（车辆列表为空时为 null） */
    private VinOption selectedVin;

    /** 选中车辆的按天趋势（包数） */
    private List<MxfTrendPoint> vinTrend;

    /** 选中车辆的包级明细（按有效触发时刻倒序，受 recordLimit 限制） */
    private List<VinRecord> vinRecords;

    /**
     * 车辆项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VinOption {
        /** data_slice.vin 原值（明文 VIN；写入方取的就是 vin_md5_mapping.vin） */
        private String vin;
        /** 展示名（非空 VIN 时与原值一致） */
        private String display;
        /** 是否已识别车辆（VIN 非空即为 true；换源后不再需要 md5 反查） */
        private boolean resolved;
        /** 切片数 */
        private long packetCount;
        /** 最后上传时间（created_at，UTC） */
        private LocalDateTime lastUploadAt;
    }

    /**
     * 切片级明细行（左上表格）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VinRecord {
        /** 切片 ID（data_slice.id） */
        private String sliceId;
        /** 有效时间（trigger_time 合法则用它，否则依次回退 start_ts / created_at） */
        private LocalDateTime time;
        /** 该切片的触发事件名（无事件时为 null） */
        private String eventName;
        /** 事件展示标题（后端保留字段，前端当前只渲染 event_name） */
        private String eventTitle;
        /** 切片名称（data_slice.name） */
        private String fileName;
        /** 是否可下载原始数据包（storage_type != 'MINIO'，归档后为 false） */
        private boolean downloadable;
    }
}
