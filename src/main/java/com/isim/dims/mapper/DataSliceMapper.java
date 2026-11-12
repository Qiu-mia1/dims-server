package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.dto.SliceSearchRequest;
import com.isim.dims.entity.DataSlice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据切片 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供基本 CRUD，
 * 额外提供 {@link #searchSlices(SliceSearchRequest)} 多条件检索。
 * 复杂动态 SQL 实现在对应的 DataSliceMapper.xml 中。
 * @feature: slice
 */
@Mapper
public interface DataSliceMapper extends BaseMapper<DataSlice> {

    /**
     * 多条件检索数据切片
     *
     * 支持条件：status、时间范围、sensor_types JSON 匹配、GPS 包围盒、
     * keyword 模糊搜索、tags 关联子查询。
     * 分页通过 MyBatis-Plus Page 插件在 Service 层包裹。
     *
     * @param request 检索请求参数（包含所有过滤条件和排序）
     * @return 匹配的 DataSlice 列表
     */
    List<DataSlice> searchSlices(@Param("request") SliceSearchRequest request);

    /**
     * 多条件计数查询（分页总数）
     *
     * 镜像 searchSlices 的所有过滤条件，确保分页总数与检索结果一致。
     *
     * @param request 检索请求参数（包含所有过滤条件）
     * @return 匹配的记录总数
     */
    long countSlices(@Param("request") SliceSearchRequest request);

    /**
     * 计算所有 data_slice 的 file_size 总和
     *
     * 使用 SQL 聚合函数 SUM 在数据库端完成计算，
     * 避免全表全字段加载到 Java 端再累加。
     *
     * @return 文件总字节数，无数据时返回 0
     */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM data_slice")
    Long sumFileSize();

    /**
     * 取每个 VIN 在指定触发时间之后（含）最新的一条 READY 切片
     *
     * <p>看板地图专用：按 vin 分组取 trigger_time 最大的记录，外层再按
     * trigger_time 倒序取前 limit 辆。PostgreSQL DISTINCT ON 一次扫描完成，
     * 避免 N+1 查询。</p>
     *
     * @param from  触发时间下界（UTC，含）
     * @param limit 最多返回车辆数
     * @return 每辆车最新一条切片
     */
    @Select("SELECT * FROM ("
            + "  SELECT DISTINCT ON (vin) * FROM data_slice"
            + "  WHERE vin IS NOT NULL AND vin <> ''"
//            + "    AND status != 'ERROR'"
            + "    AND status IN ('READY', 'WORN')"
            + "    AND trigger_time IS NOT NULL AND trigger_time >= #{from}"
            + "  ORDER BY vin, trigger_time DESC"
            + ") t ORDER BY trigger_time DESC LIMIT #{limit}")
    List<DataSlice> selectLatestSlicesPerVin(@Param("from") LocalDateTime from,
                                            @Param("limit") int limit);

    /**
     * 单车看板：拉取看板口径下的切片列表（含 slice_stats 的车型）
     *
     * <p>为什么要走自定义 SQL 而不是 {@code selectList(QueryWrapper)}：
     * {@code DataSlice.vehicleType} 声明为 {@code @TableField(exist = false)}，
     * 是 slice_stats LEFT JOIN 出来的冗余列，MP 拼列清单时不会带上它。
     * 看板右列要展示「项目」，取名口径与数据包列表一致（车型），故显式 JOIN。</p>
     *
     * <p>{@code status != 'ERROR'}：导入失败的切片不算「已上传数据」，
     * 与 {@link #selectLatestSlicesPerVin} 的过滤口径保持一致。</p>
     *
     * <p>slice_stats 对 slice_id 有 UNIQUE 约束，1:1 关联，不会行倍增。</p>
     *
     * @param limit 最多返回多少条（按入库时间倒序截断，避免全表进内存）
     * @return 看板用切片列表（vehicle_type 已填充，无统计记录时为 null）
     */
    @Select("SELECT ds.id, ds.name, ds.vin, ds.event_name, ds.trigger_time, ds.start_ts, ds.end_ts, "
            + "ds.created_at, ds.storage_type, ds.source_type, ds.system_name, ss.vehicle_type AS vehicle_type "
            + "FROM data_slice ds "
            + "LEFT JOIN slice_stats ss ON ss.slice_id = ds.id "
            + "WHERE ds.status != 'ERROR' "
            + "ORDER BY ds.created_at DESC LIMIT #{limit}")
    List<DataSlice> selectDashboardRows(@Param("limit") int limit);

    /**
     * 从 cfdi_meta LEFT JOIN 统计分布（project/car_type/event_name）
     *
     * LEFT JOIN 确保 BAG 类型（cfdi_meta 为 NULL）也被计入 "N/A"。
     * 列名已通过 Service 层白名单校验，防止 SQL 注入。
     *
     * @param column cfdi_meta 列名（car_type / project / event_name）
     * @return label + count 结果列表
     */
    @Select("SELECT COALESCE(cm.${column}, 'N/A') AS label, COUNT(*) AS cnt " +
            "FROM data_slice LEFT JOIN cfdi_meta cm ON data_slice.id = cm.slice_id " +
            "GROUP BY COALESCE(cm.${column}, 'N/A')")
    List<Map<String, Object>> selectCfdiDistribution(@Param("column") String column);

    /**
     * 查询标定缺失的 CFDI 链路切片 ID 列表
     *
     * LEFT JOIN + GROUP BY + HAVING COUNT(cc.id) = 0 单次查询替代 N+1 循环。
     * 判别依据：cfdi_meta 存在性（CFDI 上传与 LOCAL 目录导入均写 cfdi_meta，
     * V19 后 source_type 为数据来源提供方，不再承载链路信息）。
     *
     * @return 标定缺失的切片 ID 列表
     */
    @Select("SELECT data_slice.id FROM data_slice " +
            "LEFT JOIN camera_calibration cc ON data_slice.id = cc.slice_id " +
            "WHERE EXISTS (SELECT 1 FROM cfdi_meta cm WHERE cm.slice_id = data_slice.id) " +
            "GROUP BY data_slice.id HAVING COUNT(cc.id) = 0")
    List<String> selectMissingCalibrationSliceIds();

    // -----------------------------------------------------------------------
    // MXF 看板专用（数据包总量 / 柱状图 / 特殊事件通知口径改为 data_slice）
    // -----------------------------------------------------------------------

    /**
     * MXF 看板「数据包总量」：统计时间范围内非失败的数据切片数量。
     *
     * <p>时间列取 {@code COALESCE(trigger_time, start_ts)}（触发时间为空回退切片开始时间）。
     * {@code status='ERROR'} 为导入/转换失败，不计入，与看板其余查询口径一致。</p>
     *
     * @param from 时间下界（UTC，含）；调用方需保证非 null（不设限时传足够早的时刻）
     * @param to   时间上界（UTC，含）；调用方需保证非 null（不设限时传足够晚的时刻）
     * @return 切片数量
     */
    @Select("SELECT COUNT(*) FROM data_slice "
            + "WHERE status != 'ERROR' AND COALESCE(trigger_time, start_ts) >= #{from} "
            + "AND COALESCE(trigger_time, start_ts) <= #{to}")
    Long countMxfSlicesInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * MXF 看板「数据包总量」柱状图：时间范围内按 {@code event_name} 分类统计切片数量。
     *
     * @param from 时间下界（UTC，含）
     * @param to   时间上界（UTC，含）
     * @return label(event_name，空值归为 N/A) + cnt，按数量倒序
     */
    @Select("SELECT COALESCE(event_name, 'N/A') AS label, COUNT(*) AS cnt FROM data_slice "
            + "WHERE event_name not in ('obf_issue', 'obf_metric') AND status != 'ERROR' AND COALESCE(trigger_time, start_ts) >= #{from} "
            + "AND COALESCE(trigger_time, start_ts) <= #{to} "
            + "GROUP BY COALESCE(event_name, 'N/A') ORDER BY cnt DESC")
    List<Map<String, Object>> selectMxfSliceCountByEventName(@Param("from") LocalDateTime from,
                                                             @Param("to") LocalDateTime to);

    /**
     * MXF 看板「特殊事件通知」：时间范围内命中指定事件名的切片。
     *
     * <p>事件名固定为 6 类：{@code imu_collision} / {@code system_crash} /
     * {@code cp_aes_trig_event} / {@code cp_ess_trig_event} / {@code unp_tkov_colli} /
     * {@code fushion_aeb}（注意拼写 fushion，勿"顺手纠正"）。</p>
     *
     * @param from  时间下界（UTC，含）
     * @param limit 最多返回条数
     * @return 命中的切片（按 {@code COALESCE(trigger_time, start_ts)} 倒序）
     */
    @Select("SELECT * FROM data_slice "
            + "WHERE event_name IN ('imu_collision', 'system_crash', 'cp_aes_trig_event', "
            + "'cp_ess_trig_event', 'unp_tkov_colli', 'fushion_aeb') "
            + "AND status != 'ERROR' "
            + "AND COALESCE(trigger_time, start_ts) >= #{from} "
            + "ORDER BY COALESCE(trigger_time, start_ts) DESC LIMIT #{limit}")
    List<DataSlice> selectRecentMxfSpecialEventSlices(@Param("from") LocalDateTime from,
                                                      @Param("limit") int limit);

    // -----------------------------------------------------------------------
    // 运营日报看板（v6）—— 实现见 mapper/DataSliceMapper.xml
    // 全部下推 SQL 聚合：不再受 dims.mxf.vehicle.max-packets 的内存截断影响。
    // -----------------------------------------------------------------------

    /**
     * 日报摘要总量（一条 SQL 出四项数字）。
     *
     * @param sourceType 数据来源提供方（MMT/ZYT/Hirain）；null / 空 = 不过滤
     * @param from       窗口起点（UTC，含）
     * @param to         窗口终点（UTC，不含）
     * @return 单行：total_files / total_vehicles / total_bytes / total_triggers
     */
    List<Map<String, Object>> selectDailyReportTotals(@Param("sourceType") String sourceType,
                                                      @Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to);

    /**
     * 日报事件分布：按原始 {@code event_name} 分组（Java 侧归 19 个桶）。
     *
     * @return 每行 event_name + cnt（event_name 可能为 null）
     */
    List<Map<String, Object>> selectDailyReportEventCounts(@Param("sourceType") String sourceType,
                                                           @Param("from") LocalDateTime from,
                                                           @Param("to") LocalDateTime to);

    /**
     * 24H 重点事件计数（固定 8 个事件，未命中的由 Service 补 0）。
     *
     * @param events 事件名清单，<b>必须非空</b>（否则 IN () 语法错误）
     */
    List<Map<String, Object>> selectKeyEventCounts(@Param("sourceType") String sourceType,
                                                   @Param("events") List<String> events,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    /**
     * 24H 重点事件明细（含 {@code system_name} 软件版本列）。
     *
     * @param events 事件名清单，<b>必须非空</b>
     * @param limit  最大行数
     */
    List<DataSlice> selectKeyEventSlices(@Param("sourceType") String sourceType,
                                         @Param("events") List<String> events,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         @Param("limit") int limit);

    // -----------------------------------------------------------------------
    // VIN-MD5 映射导入后的回写（VIN 补齐）
    // -----------------------------------------------------------------------

    /**
     * 按 {@code import_md5} 批量回写切片的 VIN 与车辆外键
     *
     * <p>场景：本地目录导入时 {@code vin_md5_mapping} 还没有对应 MD5，切片被写成
     * VIN 为空（或兜底成 {@code vin = md5, vin_md5_id = 1}）。车辆信息 Excel 导入
     * 补齐映射后，用一条 SQL 把这些历史切片一次性纠正过来。</p>
     *
     * <p>等价 SQL（PostgreSQL）：</p>
     * <pre>
     * UPDATE data_slice d
     *    SET vin = v.vin, vin_md5_id = v.id
     *   FROM vin_md5_mapping v
     *  WHERE d.import_md5 = v.md5
     *    AND v.md5 IN ('md5_1', 'md5_2', ...)
     *    AND (d.vin IS DISTINCT FROM v.vin OR d.vin_md5_id IS DISTINCT FROM v.id);
     * </pre>
     *
     * <p>{@code IS DISTINCT FROM} 让「已经是正确 VIN」的行不被重复更新，
     * 返回值才是真正被纠正的行数；{@code md5s} 必须非空（否则 IN () 语法错误）。</p>
     *
     * @param md5s 本次导入成功的 bag 文件 MD5 清单（非空）
     * @return 被回写的切片行数
     */
    @Update("<script>"
            + "UPDATE data_slice d SET vin = v.vin, vin_md5_id = v.id "
            + "FROM vin_md5_mapping v "
            + "WHERE d.import_md5 = v.md5 AND v.md5 IN "
            + "<foreach collection='md5s' item='md5' open='(' separator=',' close=')'>#{md5}</foreach> "
            + "AND (d.vin IS DISTINCT FROM v.vin OR d.vin_md5_id IS DISTINCT FROM v.id)"
            + "</script>")
    int backfillVinByImportMd5(@Param("md5s") List<String> md5s);
}
