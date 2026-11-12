package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 切片检索参数
 *
 * 对应 GET /v1/data-slices 的查询参数。
 * 字段与 OpenAPI listDataSlices operation 的 query parameters 对齐。
 *
 * 查询参数通过 Controller 层由 {@code @RequestParam Map<String,String>} 接收
 * 再经 Jackson ObjectMapper（snake_case 配置）转换为本对象。
 * 字段上的 {@code @JsonProperty} 提供明确的 snake_case 映射。
 * @feature: slice
 */
@Data
public class SliceSearchRequest {

    /** 数据起始时间（含），UTC RFC3339，如 "2026-07-24T09:00:00Z" */
    @JsonProperty("start_ts")
    private String startTs;

    /** 数据结束时间（含），UTC RFC3339 */
    @JsonProperty("end_ts")
    private String endTs;

    /** 逗号分隔的传感器类型，如 "camera,lidar,radar" */
    @JsonProperty("sensor_types")
    private String sensorTypes;

    /** 逗号分隔的标签名（须全部匹配） */
    @JsonProperty("tags")
    private String tags;

    /** 中心纬度（配合 lon + radius 使用），WGS84 */
    @JsonProperty("lat")
    private Double lat;

    /** 中心经度，WGS84 */
    @JsonProperty("lon")
    private Double lon;

    /** 搜索半径，unit: 米 */
    @JsonProperty("radius")
    private Integer radius;

    /** GPS 包围盒最小纬度（由 Service 层从 lat/lon/radius 计算填充） */
    @JsonProperty("min_lat")
    private Double minLat;

    /** GPS 包围盒最小经度（由 Service 层从 lat/lon/radius 计算填充） */
    @JsonProperty("min_lon")
    private Double minLon;

    /** GPS 包围盒最大纬度（由 Service 层从 lat/lon/radius 计算填充） */
    @JsonProperty("max_lat")
    private Double maxLat;

    /** GPS 包围盒最大经度（由 Service 层从 lat/lon/radius 计算填充） */
    @JsonProperty("max_lon")
    private Double maxLon;

    /** 文件名/备注模糊搜索 */
    @JsonProperty("keyword")
    private String keyword;

    /** 切片状态过滤：READY | ERROR */
    @JsonProperty("status")
    private String status;

    /** 数据来源提供方过滤：MMT | ZYT | Hirain（存量数据为 null） */
    @JsonProperty("source_type")
    private String sourceType;

    /** 事件名模糊匹配（cfdi_meta.event_name） */
    @JsonProperty("event_name")
    private String eventName;

    /**
     * 筛选器名（{@code camera_event_config.json} 的 {@code events.*.filter}）。
     *
     * <p>与智驾事件看板的筛选器下拉同源：前端只送筛选器名，由 Service 层经
     * {@code CameraEventConfig#getFilterEventIndex()} 展开成 {@link #eventNames}。
     * 一个筛选器可能对应多个事件（如 {@code highbeam_on_filter} → 高中/中远光），按并集命中。</p>
     */
    @JsonProperty("filter")
    private String filter;

    /**
     * filter 展开后的事件名列表（由 Service 层填充，不接受外部传入）。
     *
     * <p>非空时 SQL 逐个 ILIKE 后取并集。⚠️ 用模糊而非精确 IN 是刻意的：
     * 库里存在 {@code apa_all_time_event_segment} 这类带后缀的行，精确匹配会漏。</p>
     */
    @JsonIgnore
    private List<String> eventNames;

    /** 车型过滤（cfdi_meta.car_type） */
    @JsonProperty("car_type")
    private String carType;

    /** 项目名过滤（cfdi_meta.project） */
    @JsonProperty("project")
    private String project;

    /** 数据来源环境过滤（cfdi_meta.data_source_env）：TEST | PROD */
    @JsonProperty("data_source_env")
    private String dataSourceEnv;

    /** VIN 码模糊匹配（cfdi_meta.vin 或 data_slice.name 文件名） */
    @JsonProperty("vin")
    private String vin;

    /** UTC 偏移微秒值精确匹配（data_slice.utc_diff_us，单位微秒） */
    @JsonProperty("utc_diff_us")
    private Long utcDiffUs;

    /** 标签关键词模糊匹配（tag.name），区别于 tags（tag_id 精确匹配） */
    @JsonProperty("tag")
    private String tag;

    /**
     * 标签名关键词预解析出的 tag_id 集合 —— <b>Service 层填充，非用户入参</b>。
     *
     * <p>{@code tag} 表已随 V29 移除（标签定义在 SIMS），SQL 无法再 JOIN tag 按名字过滤；
     * {@code DataSliceServiceImpl.search()} 用 {@code SimsTagCatalog} 词表把 {@link #tag}
     * 模糊匹配成 id 集合塞进来，Mapper XML 按 {@code data_slice_tag.tag_id IN (...)} 过滤。
     */
    private java.util.List<String> tagIds;

    /** 是否存在接管（基于 slice_stats.takeover_count > 0） */
    @JsonProperty("has_takeover")
    private Boolean hasTakeover;

    /**
     * 功能激活筛选，逗号分隔：NOA | AEB | APA | LCC | ACC（大小写不敏感，LCC+ 等价 LCC）。
     *
     * <p>多选为 <b>AND</b> 语义：勾选多个功能 = 筛「同一切片内这些功能都激活过」的 case。
     * 基于 slice_stats 的 noa_active / aeb_active / apa_active / lcc_active / acc_active
     * 布尔冗余列（统计提取时「切片内任一帧激活过」即为 TRUE）。</p>
     */
    @JsonProperty("features")
    private String features;

    /**
     * features 解析后的功能名规范列表（大写 NOA/AEB/APA/LCC/ACC）。
     *
     * <p>Service 层填充，不接受外部传入；Mapper XML 逐个
     * {@code contains} 判定后拼 AND 条件，空列表表示不过滤。</p>
     */
    @JsonIgnore
    private List<String> featureNames;

    /** 上传日期起始范围（data_slice.created_at >=），UTC RFC3339 */
    @JsonProperty("start_date")
    private String startDate;

    /** 上传日期结束范围（data_slice.created_at <=），UTC RFC3339 */
    @JsonProperty("end_date")
    private String endDate;

    /** 触发时间起始范围（data_slice.trigger_time >=），UTC RFC3339 */
    @JsonProperty("trigger_start")
    private String triggerStart;

    /** 触发时间结束范围（data_slice.trigger_time <=），UTC RFC3339 */
    @JsonProperty("trigger_end")
    private String triggerEnd;

    /** 页码，从 1 开始，默认 1 */
    @JsonProperty("page")
    private Integer page = 1;

    /** 每页条数，默认 50，最大 500 */
    @JsonProperty("page_size")
    private Integer pageSize = 50;

    /**
     * 排序表达式，格式 "field:direction"，默认 "created_at:desc"。
     *
     * <p>可选列：created_at / start_ts / end_ts / trigger_time / file_size /
     * name / event_name，以及特殊值 {@code preset}。</p>
     *
     * <p>{@code preset:asc} 表示按 {@link #sliceIds} 的传入顺序返回，用于保留
     * 语义检索（以文搜图 / 以图搜图）Reranker 打分的排序结果；
     * {@code preset:desc} 为传入顺序倒序。sliceIds 为空时降级为 created_at DESC。</p>
     */
    @JsonProperty("sort")
    private String sort = "created_at:desc";

    /**
     * 图像语义检索模式（以文搜图）：text | image | feature
     *
     * <p>text 模式由 Service 层调用 ImageSearchService 得到匹配 sliceId 集合后，
     * 与其它条件一起过滤列表；image / feature 模式因需要上传文件或超长向量，
     * 由前端先调用搜图接口得到 sliceId 集合，再通过 {@link #sliceIds} 传入。</p>
     */
    @JsonProperty("image_search_mode")
    private String imageSearchMode;

    /** 以文搜图检索文本（image_search_mode=text 时使用） */
    @JsonProperty("image_search_text")
    private String imageSearchText;

    /**
     * 按 sliceId 集合过滤（逗号分隔）。
     *
     * <p>由图像语义检索（以图搜图 / 特征搜图）得到的匹配切片 ID 集合，
     * 或 text 模式 Service 层回填。为空时表示搜图无匹配。</p>
     */
    @JsonProperty("slice_ids")
    private String sliceIds;

    /**
     * 起始行号（由 Service 层计算填充），用于 SQL LIMIT OFFSET
     */
    private Integer startRow;
}
