package com.isim.dims.service;

import com.isim.dims.entity.MxfOfflineNote;

import java.util.List;

/**
 * MXF 积压车辆忽略名单服务。
 *
 * <p>语义：为某辆车填写备注 = 把它从「数据异常车辆」统计里摘出去（如已返厂维修、
 * 测试车长期离线）。撤销 = 重新纳入统计。</p>
 *
 * @feature: mxf
 */
public interface MxfOfflineNoteService {

    /**
     * 全部忽略记录（按更新时间倒序）。
     */
    List<MxfOfflineNote> listAll();

    /**
     * 为车辆写入 / 覆盖备注（按 {@code vin} 唯一）。
     *
     * @param vin      车辆标识（mxf_packet.vin 原值）
     * @param note     备注内容，必填，去除首尾空白后长度 ≤ 500
     * @param operator 操作人（JWT subject，可为 null）
     * @return 落库后的实体（含 updatedAt）
     * @throws IllegalArgumentException vin 为空或备注为空 / 超长
     */
    MxfOfflineNote saveNote(String vin, String note, String operator);

    /**
     * 撤销某辆车的忽略状态。
     *
     * @return 是否确实删掉了一条记录（false = 本来就没有备注）
     * @throws IllegalArgumentException vin 为空
     */
    boolean removeNote(String vin);
}
