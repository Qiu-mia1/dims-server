package com.isim.dims.dto;

import lombok.Data;

/**
 * 特征搜图请求
 *
 * @feature: visualization
 */
@Data
public class FeatureImageSearchRequest {
    /** embedding 特征向量（dims.siglip.embed-dims 维） */
    private double[] feature;
    /** 返回条数上限（默认 50） */
    private Integer limit;
}