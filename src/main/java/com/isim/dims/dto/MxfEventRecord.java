package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事件明细行（中间 / 右列两个面板的表格行）。
 *
 * <p>粒度 = 包：V27 起 {@code mxf_event_record} 已删除，一行即一个包命中一次事件。
 * 被中间（筛选器视角）与右侧（12H 重点事件统计）共用，故提为顶层 DTO。</p>
 *
 * @feature: mxf
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfEventRecord {

    /** 事件名（data_slice.event_name，切片级单事件） */
    private String eventName;

    /** 事件展示标题（后端保留字段，前端当前只渲染 event_name） */
    private String eventTitle;

    /** 车辆标识（data_slice.vin，明文 VIN；为 null 表示该切片未关联车辆） */
    private String vin;

    /** 车辆展示名（与 vin 一致；空值由前端兜底为「-」） */
    private String vinDisplay;

    /** 车辆是否已识别（VIN 非空） */
    private boolean vinResolved;

    /** 有效触发时刻（trigger_time → start_ts → created_at 三级兜底） */
    private LocalDateTime triggerTime;

    /** 切片 ID（data_slice.id） */
    private String sliceId;

    /** 切片名称（data_slice.name） */
    private String fileName;

    /**
     * 软件版本号（data_slice.system_name，来自 CFDI meta 的 {@code system_name}）。
     *
     * <p>可空：老数据（V30 迁移之前入库）与未带该字段的源包都拿不到值，前端兜底展示 {@code -}。</p>
     */
    private String systemName;

    /** 是否可下载原始数据包（storage_type != 'MINIO'，归档后为 false） */
    private boolean downloadable;
}
