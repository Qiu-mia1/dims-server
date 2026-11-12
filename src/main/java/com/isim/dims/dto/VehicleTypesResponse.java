package com.isim.dims.dto;

import lombok.Data;

import java.util.List;

/**
 * 车型下拉选项响应
 *
 * 对应 GET /v1/dashboard/vehicle-types 的响应。
 * 数据源：SELECT DISTINCT vehicle_type FROM slice_stats WHERE vehicle_type IS NOT NULL。
 * @feature: dashboard
 */
@Data
public class VehicleTypesResponse {

    /** 车型选项列表 */
    private List<TypeItem> types;

    /**
     * 车型选项项
     */
    @Data
    public static class TypeItem {

        /** 选项值（= vehicle_type 原文） */
        private String value;

        /** 展示标签（当前与 value 相同） */
        private String label;
    }
}
