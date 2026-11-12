package com.isim.dims.dto;

import lombok.Data;

/**
 * 单车 MXF 看板「添加备注」请求体（POST /v1/mxf/offline-notes）。
 *
 * @feature: mxf
 */
@Data
public class MxfOfflineNoteRequest {

    /** 车辆标识（{@code mxf_packet.vin} 原值，即 32 位 md5） */
    private String vin;

    /** 备注内容（必填，去除首尾空白后 ≤ 500 字符） */
    private String note;
}
