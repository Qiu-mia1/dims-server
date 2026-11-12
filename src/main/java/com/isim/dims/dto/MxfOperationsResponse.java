package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智驾运营数据看板（V1.1 重写版）汇总响应。
 *
 * <p>数据来源：/mmt-ads/Momenta-MXF/LUS_CX835ICA_sop/ 目录下的 MXF 文件（导入包的 section_stats 快照）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MxfOperationsResponse {

    /** 时间筛选：today/week/month/year/all/custom */
    private String timeRange;

    /** 辅助驾驶总里程（km）——AUTO-INFO auto_dis_in_last_duration_(m) 累加 */
    private Double autoDistanceKm;

    /** 辅助驾驶总时长（h）——AUTO-INFO auto_duration_(s) 累加 */
    private Double autoDurationHours;

    /** 最长智驾距离（单次，m）——HNP-INFO hnp_dis_in_last_duration_(m) 所有 MXF 文件取最大值 */
    private Double maxHnpDisM;

    /** 最长智驾距离（单次，m）——UNP-INFO unp_dis_in_last_duration_(m) 所有 MXF 文件取最大值 */
    private Double maxUnpDisM;

    /** 最长智驾距离（单次，m）——V2-RNP-INFO rnp_dis_m 所有 MXF 文件取最大值 */
    private Double maxRnpDisM;

    /** 辅助泊车使用总次数——APA/RPA/AVP/LPNP/NSP/LRAS 的 xxx_end_status 字段累加 */
    private Long parkingUseCount;

    /** 当前数据库中车辆数量（去重 VIN） */
    private Long vehicleCount;

    /** 单车平均泊车使用次数 = 泊车使用次数 / 车辆数量 */
    private Double avgParkingUsePerVehicle;

    /** 辅助泊车使用车辆占比（0~1） */
    private Double parkingVehicleRatio;

    /** 行车看板：AEB/FCW/AES/ADB/FCTA/FCTB/RCTB/CMSR/LKA/LDW/LAEB 触发次数 */
    private List<DrivingItem> driving;

    /** 泊车看板：APA/RPA/AVP/LPNP/NSP/LRAS（循迹倒车） */
    private List<ParkingItem> parking;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DrivingItem {
        /** 事件 SECTION 名，如 AEB-ACTIVATE */
        private String key;
        /** 显示名，如 AEB */
        private String label;
        /** 触发次数（各 MXF 文件累加） */
        private Long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParkingItem {
        /** 功能键：APA/RPA/AVP/LPNP/NSP/LRAS */
        private String key;
        /** 显示名 */
        private String label;
        /** 平均激活次数（次/车） */
        private Double avgUsePerVehicle;
        /** 平均泊车时长（s）——xxx_parking_duration_(s) / 激活次数 */
        private Double avgDurationS;
        /** 平均揉库次数——gear_switch_times / 激活次数 */
        private Double avgGearSwitch;
        /** 平均成功率——结束状态为 2 的占比（0~1） */
        private Double successRate;
        /** 平均接管次数——type 为 manual 的行数累加 */
        private Long manualCount;
    }
}
