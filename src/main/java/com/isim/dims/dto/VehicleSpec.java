package com.isim.dims.dto;

import lombok.Data;

/**
 * 车体尺寸（自车轮廓绘制）
 *
 * 对应 OpenAPI schema: VehicleSpec。
 * required: [length_m, width_m, wheelbase_m]
 * @feature: visualization
 */
@Data
public class VehicleSpec {

    /** 车长，unit: m */
    private Double lengthM;

    /** 车宽，unit: m */
    private Double widthM;

    /** 轴距，unit: m */
    private Double wheelbaseM;
}
