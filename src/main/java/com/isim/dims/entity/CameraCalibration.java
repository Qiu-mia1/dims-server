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
 * 摄像头标定参数表 (camera_calibration)
 *
 * CFDI 数据包专用，记录摄像头内参和外参标定数据。
 * 与 data_slice 为一对多关系（一个切片有多路摄像头）。
 * @feature: calibration
 */
@Data
@TableName("camera_calibration")
public class CameraCalibration {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("slice_id")
    private String sliceId;

    @TableField("sensor_name")
    private String sensorName;

    @TableField("serial_number")
    private String serialNumber;

    @TableField(value = "vehicle_xyz", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String vehicleXyz;

    @TableField("sensor_type")
    private String sensorType;

    @TableField("camera_model")
    private String cameraModel;

    @TableField("timestamp_shift")
    private Double timestampShift;

    /** JSON: 旋转向量 [rx,ry,rz] rad */
    @TableField(value = "r_s2b", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String rS2b;

    /** JSON: 平移向量 [tx,ty,tz] m */
    @TableField(value = "t_s2b", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String tS2b;

    @TableField("fx")
    private Double fx;

    @TableField("fy")
    private Double fy;

    @TableField("cx")
    private Double cx;

    @TableField("cy")
    private Double cy;

    @TableField("kc2")
    private Double kc2;

    @TableField("kc3")
    private Double kc3;

    @TableField("kc4")
    private Double kc4;

    @TableField("kc5")
    private Double kc5;

    /** TINYINT(1): 0=否, 1=是, DEFAULT 0 */
    @TableField("is_fisheye")
    private Integer isFisheye;

    @TableField("line_exposure_delay")
    private Double lineExposureDelay;

    @TableField("width")
    private Integer width;

    @TableField("height")
    private Integer height;

    /** JSON: 建议裁切区域 */
    @TableField(value = "suggested_rect_region", jdbcType = JdbcType.OTHER,
            typeHandler = SensorStream.JsonbTypeHandler.class)
    private String suggestedRectRegion;

    @TableField("calib_version")
    private String calibVersion;

    @TableField("calib_details")
    private String calibDetails;

    @TableField("calib_date")
    private String calibDate;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
