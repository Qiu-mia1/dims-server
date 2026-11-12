package com.isim.dims.dto;

import com.isim.dims.entity.SliceStats;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片统计指标响应 DTO.
 *
 * <p>将 {@link SliceStats} 实体转换为 API 响应，字段名与
 * Python 脚本输出一致（snake_case JSON）。</p>
 *
 * @feature: dashboard
 */
@Data
public class SliceStatsResponse {

    private String id;
    private String sliceId;
    private String taskId;

    // 车辆信息
    private String vehicleType;
    private Double vehicleMileageStart;
    private Double vehicleMileageEnd;
    private Double vehicleMileageDelta;

    // 车速统计 (unit: m/s)
    private Double speedAvg;
    private Double speedMax;
    private Double speedMin;

    // 道路场景
    private String roadScene;
    private String roadSceneSource;

    // 功能状态快照（《智能功能状态灯 · 信号源说明》）
    private Boolean noaActive;
    private Boolean aebActive;
    private Boolean apaActive;
    private Boolean accActive;
    private Boolean lccActive;
    private Boolean roamingActive;
    private Boolean avpActive;
    private Boolean fcwActive;
    private Boolean aesActive;
    private Boolean essActive;

    // 功能状态统计
    private Integer noaActivationCount;
    private Double noaTotalDurationS;
    private Integer noaSuccessCount;
    private Integer noaFailCount;
    private Integer noaExitCount;
    private Double noaMileageKm;
    private Double noaSuccessRate;
    private Integer aebTriggerCount;
    private Integer aebTotalScenes;
    private Integer apaTaskCount;
    private Integer apaSuccessCount;
    private Double apaSuccessRate;

    // 接管/介入
    private Integer takeoverCount;
    private Double takeoverRate;
    private String takeoverReasons;
    private Integer interventionCount;
    private Double interventionRate;
    private String sceneBreakdown;

    // 工况
    private Double curvatureAvg;
    private Double curvatureMax;
    private Double gnssSatNumAvg;
    private Double gnssHdopAvg;
    private Integer laneChangeCountLeft;
    private Integer laneChangeCountRight;
    private Boolean highwayMode;

    // 元信息
    private String status;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SliceStatsResponse fromEntity(SliceStats s) {
        SliceStatsResponse r = new SliceStatsResponse();
        r.id = s.getId();
        r.sliceId = s.getSliceId();
        r.taskId = s.getTaskId();
        r.vehicleType = s.getVehicleType();
        r.vehicleMileageStart = s.getVehicleMileageStart();
        r.vehicleMileageEnd = s.getVehicleMileageEnd();
        r.vehicleMileageDelta = s.getVehicleMileageDelta();
        r.speedAvg = s.getSpeedAvg();
        r.speedMax = s.getSpeedMax();
        r.speedMin = s.getSpeedMin();
        r.roadScene = s.getRoadScene();
        r.roadSceneSource = s.getRoadSceneSource();
        r.noaActive = s.getNoaActive();
        r.aebActive = s.getAebActive();
        r.apaActive = s.getApaActive();
        r.accActive = s.getAccActive();
        r.lccActive = s.getLccActive();
        r.roamingActive = s.getRoamingActive();
        r.avpActive = s.getAvpActive();
        r.fcwActive = s.getFcwActive();
        r.aesActive = s.getAesActive();
        r.essActive = s.getEssActive();
        r.noaActivationCount = s.getNoaActivationCount();
        r.noaTotalDurationS = s.getNoaTotalDurationS();
        r.noaSuccessCount = s.getNoaSuccessCount();
        r.noaFailCount = s.getNoaFailCount();
        r.noaExitCount = s.getNoaExitCount();
        r.noaMileageKm = s.getNoaMileageKm();
        r.noaSuccessRate = s.getNoaSuccessRate();
        r.aebTriggerCount = s.getAebTriggerCount();
        r.aebTotalScenes = s.getAebTotalScenes();
        r.apaTaskCount = s.getApaTaskCount();
        r.apaSuccessCount = s.getApaSuccessCount();
        r.apaSuccessRate = s.getApaSuccessRate();
        r.takeoverCount = s.getTakeoverCount();
        r.takeoverRate = s.getTakeoverRate();
        r.takeoverReasons = s.getTakeoverReasons();
        r.interventionCount = s.getInterventionCount();
        r.interventionRate = s.getInterventionRate();
        r.sceneBreakdown = s.getSceneBreakdown();
        r.curvatureAvg = s.getCurvatureAvg();
        r.curvatureMax = s.getCurvatureMax();
        r.gnssSatNumAvg = s.getGnssSatNumAvg();
        r.gnssHdopAvg = s.getGnssHdopAvg();
        r.laneChangeCountLeft = s.getLaneChangeCountLeft();
        r.laneChangeCountRight = s.getLaneChangeCountRight();
        r.highwayMode = s.getHighwayMode();
        r.status = s.getStatus();
        r.errorMsg = s.getErrorMsg();
        r.createdAt = s.getCreatedAt();
        r.updatedAt = s.getUpdatedAt();
        return r;
    }
}
