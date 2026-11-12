package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 切片列表响应（分页）
 *
 * 对应 OpenAPI schema: SliceListResponse
 * @feature: slice
 */
@Data
public class SliceListResponse {

    /** 切片列表 */
    @JsonProperty("list")
    private List<SliceItem> items;

    /** 总记录数 */
    private Long total;

    /** 当前页码 */
    private Integer page;

    /** 每页条数 */
    @JsonProperty("size")
    private Integer pageSize;

    /**
     * 数据切片摘要项
     *
     * 对应 OpenAPI schema: DataSlice（列表摘要）
     */
    @Data
    public static class SliceItem {

        /** 切片 UUID */
        private String id;

        /** 切片名称 */
        private String name;

        /** 原始 bag 文件字节数 */
        private Long fileSize;

        /** 数据时长，unit: 毫秒 */
        private Long durationMs;

        /** 数据起始时间，UTC RFC3339 */
        private String startTs;

        /** 数据结束时间，UTC RFC3339 */
        private String endTs;

        /**
         * 触发时间，UTC RFC3339
         * 来源：本地目录导入 meta 的 trigger_timestamp_us + utc_diff_us（非 CFDI / meta 缺失为 null）；
         * 前端按东八区（北京时间）展示。
         */
        @JsonProperty("trigger_time")
        private String triggerTime;

        /** GPS 地理包围盒，WGS84 */
        private GpsBbox gpsBbox;

        /** 切片的传感器类型列表 */
        private List<String> sensorTypes;

        /** 总消息数 */
        private Long msgCountTotal;

        /** 标签引用列表（id/name/categoryPath；名称由 SIMS 词表缓存解析） */
        private List<TagRef> tags;

        /** 切片状态：UPLOADING | CONVERTING | PARSING | TAGGING | READY | ERROR */
        private String status;

        /**
         * 按需加载状态（由 status + import_bos_key 推导）：
         * NOT_LOADED=仅 meta + 前视鱼眼（未加载全量数据，可播放鱼眼预览）|
         * LOADING=加载中 | LOADED=已加载完成（可回放/下载）
         */
        @JsonProperty("load_state")
        private String loadState;

        private String errorMsg;

        private String eventName;

        /** 数据来源类型：BAG | CFDI */
        @JsonProperty("source_type")
        private String sourceType;

        /** 导出入口路径 */
        private String downloadUrl;

        /** 切片创建时间，UTC RFC3339 */
        private String createdAt;

        /** 车辆型号（slice_stats.vehicle_type LEFT JOIN；无统计记录时为 null） */
        private String vehicleType;

        /** 异常标记：status='ERROR' 或 slice_stats 无统计记录时为 true */
        private Boolean hasAlerts;

        /** VIN 码（本地导入反查结果，未匹配为 null） */
        private String vin;

        /** CFDI meta 时钟偏移（微秒，非 CFDI 来源为 null） */
        @JsonProperty("utc_diff_us")
        private Long utcDiffUs;

        /** NOA 是否有激活段（slice_stats.noa_active） */
        @JsonProperty("noa_active")
        private Boolean noaActive;

        /** AEB 是否有触发（slice_stats.aeb_active） */
        @JsonProperty("aeb_active")
        private Boolean aebActive;

        /** APA 是否有任务（slice_stats.apa_active） */
        @JsonProperty("apa_active")
        private Boolean apaActive;

        /** ACC 是否有激活段（slice_stats.acc_active） */
        @JsonProperty("acc_active")
        private Boolean accActive;

        /** LCC+ 是否有激活段（slice_stats.lcc_active） */
        @JsonProperty("lcc_active")
        private Boolean lccActive;

        /** NOA 漫游是否有激活段（slice_stats.roaming_active） */
        @JsonProperty("roaming_active")
        private Boolean roamingActive;

        /** AVP/RADS 是否有激活段（slice_stats.avp_active） */
        @JsonProperty("avp_active")
        private Boolean avpActive;

        /** FCW 是否有触发（slice_stats.fcw_active） */
        @JsonProperty("fcw_active")
        private Boolean fcwActive;

        /** AES 是否有触发（slice_stats.aes_active） */
        @JsonProperty("aes_active")
        private Boolean aesActive;

        /** ESS 是否有触发（slice_stats.ess_active） */
        @JsonProperty("ess_active")
        private Boolean essActive;
    }
}
