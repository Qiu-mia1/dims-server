package com.isim.dims.entity;

import org.apache.ibatis.type.JdbcType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片统计指标表 (slice_stats)
 *
 * 驾驶舱统计提取任务（dashboard_stats_task）SUCCESS 后写入，
 * 与 data_slice 1:1（slice_id UNIQUE）。
 * 单位约定（宪法第 7 节）：里程 unit: km，车速 unit: m/s，
 * 时长 unit: s，曲率 unit: 1/m；时间字段一律 UTC。
 * status 字段追踪最新一次提取状态（PENDING|RUNNING|SUCCESS|FAILED）。
 * @feature: dashboard
 */
@Data
@TableName("slice_stats")
public class SliceStats {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    /** 最近一次 SUCCESS 提取任务 id */
    @TableField("task_id")
    private String taskId;

    /** 车辆型号（/config_service/vpi vehicle_type） */
    @TableField("vehicle_type")
    private String vehicleType;

    /** 起始里程, unit: km */
    @TableField("vehicle_mileage_start")
    private Double vehicleMileageStart;

    /** 结束里程, unit: km */
    @TableField("vehicle_mileage_end")
    private Double vehicleMileageEnd;

    /** 采集里程 = end - start, unit: km */
    @TableField("vehicle_mileage_delta")
    private Double vehicleMileageDelta;

    /** 平均车速, unit: m/s */
    @TableField("speed_avg")
    private Double speedAvg;

    /** 最高车速, unit: m/s */
    @TableField("speed_max")
    private Double speedMax;

    /** 最低车速, unit: m/s */
    @TableField("speed_min")
    private Double speedMin;

    /** 主要道路场景 URBAN|HIGHWAY|TUNNEL|TOLL_GATE|ROUNDABOUT */
    @TableField("road_scene")
    private String roadScene;

    /** 场景来源: unp_planning|ddld|mff|majority_vote */
    @TableField("road_scene_source")
    private String roadSceneSource;

    /** NOA 是否有激活段 */
    @TableField("noa_active")
    private Boolean noaActive;

    /** AEB 是否有触发 */
    @TableField("aeb_active")
    private Boolean aebActive;

    /** APA 是否有任务 */
    @TableField("apa_active")
    private Boolean apaActive;

    /** ACC 是否有激活段 */
    @TableField("acc_active")
    private Boolean accActive;

    /** LCC+ 是否有激活段 */
    @TableField("lcc_active")
    private Boolean lccActive;

    /** NOA 漫游是否有激活段（debug.sm 模式码 3） */
    @TableField("roaming_active")
    private Boolean roamingActive;

    /** AVP/RADS 是否有激活段（debug.sm 模式码 8/9） */
    @TableField("avp_active")
    private Boolean avpActive;

    /** FCW 是否有触发 */
    @TableField("fcw_active")
    private Boolean fcwActive;

    /** AES 是否有触发 */
    @TableField("aes_active")
    private Boolean aesActive;

    /** ESS 是否有触发 */
    @TableField("ess_active")
    private Boolean essActive;

    /** NOA 激活次数 */
    @TableField("noa_activation_count")
    private Integer noaActivationCount;

    /** NOA 总激活时长, unit: s */
    @TableField("noa_total_duration_s")
    private Double noaTotalDurationS;

    /** NOA 成功退出次数 */
    @TableField("noa_success_count")
    private Integer noaSuccessCount;

    /** NOA 失败退出次数 */
    @TableField("noa_fail_count")
    private Integer noaFailCount;

    /** NOA 总退出次数 */
    @TableField("noa_exit_count")
    private Integer noaExitCount;

    /** NOA 运行里程, unit: km */
    @TableField("noa_mileage_km")
    private Double noaMileageKm;

    /** NOA 成功率 0-1 */
    @TableField("noa_success_rate")
    private Double noaSuccessRate;

    /** AEB 触发次数 */
    @TableField("aeb_trigger_count")
    private Integer aebTriggerCount;

    /** AEB 感知数据覆盖的场景数 */
    @TableField("aeb_total_scenes")
    private Integer aebTotalScenes;

    /** AEB 激活总时长, unit: s */
    @TableField("aeb_total_duration_s")
    private Double aebTotalDurationS;

    /** AEB 激活期间里程, unit: km */
    @TableField("aeb_mileage_km")
    private Double aebMileageKm;

    /** APA 泊车任务次数 */
    @TableField("apa_task_count")
    private Integer apaTaskCount;

    /** APA 泊车成功次数 */
    @TableField("apa_success_count")
    private Integer apaSuccessCount;

    /** APA 泊车成功率 0-1 */
    @TableField("apa_success_rate")
    private Double apaSuccessRate;

    /** APA 任务激活总时长, unit: s */
    @TableField("apa_total_duration_s")
    private Double apaTotalDurationS;

    /** APA 激活期间里程, unit: km */
    @TableField("apa_mileage_km")
    private Double apaMileageKm;

    /** ACC 激活次数 */
    @TableField("acc_activation_count")
    private Integer accActivationCount;

    /** ACC 激活总时长, unit: s */
    @TableField("acc_total_duration_s")
    private Double accTotalDurationS;

    /** ACC 激活期间里程, unit: km */
    @TableField("acc_mileage_km")
    private Double accMileageKm;

    /** LCC+ 激活次数 */
    @TableField("lcc_activation_count")
    private Integer lccActivationCount;

    /** LCC+ 激活总时长, unit: s */
    @TableField("lcc_total_duration_s")
    private Double lccTotalDurationS;

    /** LCC+ 激活期间里程, unit: km */
    @TableField("lcc_mileage_km")
    private Double lccMileageKm;

    /** 接管次数（driver_work_type 变迁） */
    @TableField("takeover_count")
    private Integer takeoverCount;

    /** 接管率, unit: 次/100km */
    @TableField("takeover_rate")
    private Double takeoverRate;

    /** 接管原因分布 {reason: count} */
    @TableField(value = "takeover_reasons", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String takeoverReasons;

    /** 介入次数（override 标志） */
    @TableField("intervention_count")
    private Integer interventionCount;

    /** 介入率, unit: 次/100km */
    @TableField("intervention_rate")
    private Double interventionRate;

    /** 按道路场景分组的各功能指标 {scene: {noa_duration_s, takeover_count, ...}} */
    @TableField(value = "scene_breakdown", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String sceneBreakdown;

    /** 平均弯道曲率, unit: 1/m */
    @TableField("curvature_avg")
    private Double curvatureAvg;

    /** 最大弯道曲率, unit: 1/m */
    @TableField("curvature_max")
    private Double curvatureMax;

    /** 平均卫星数 */
    @TableField("gnss_sat_num_avg")
    private Double gnssSatNumAvg;

    /** 平均 HDOP */
    @TableField("gnss_hdop_avg")
    private Double gnssHdopAvg;

    /** 左变道次数 */
    @TableField("lane_change_count_left")
    private Integer laneChangeCountLeft;

    /** 右变道次数 */
    @TableField("lane_change_count_right")
    private Integer laneChangeCountRight;

    /** 高速优先是否开启 */
    @TableField("highway_mode")
    private Boolean highwayMode;

    /**
     * PENDING|RUNNING|SUCCESS|FAILED（最新一次提取状态）
     */
    @TableField("status")
    private String status;

    /** 错误信息（FAILED 时非空） */
    @TableField("error_msg")
    private String errorMsg;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
