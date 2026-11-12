package com.isim.dims.dto;

import lombok.Data;

/**
 * GPS 地理包围盒，WGS84 坐标系
 *
 * 对应 OpenAPI schema: GpsBbox
 * 可被 SliceListResponse.SliceItem 和 SliceDetailResponse 复用。
 * @feature: trajectory
 */
@Data
public class GpsBbox {

    /** 最小纬度，unit: degrees, frame: WGS84 */
    private Double minLat;

    /** 最小经度，unit: degrees, frame: WGS84 */
    private Double minLon;

    /** 最大纬度，unit: degrees, frame: WGS84 */
    private Double maxLat;

    /** 最大经度，unit: degrees, frame: WGS84 */
    private Double maxLon;
}
