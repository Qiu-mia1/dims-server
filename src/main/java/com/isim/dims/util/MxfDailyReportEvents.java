package com.isim.dims.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运营日报看板「事件类型分布」的 19 类固定桶（桶 key / 展示标题 / 原始 event_name 归类规则）。
 *
 * <h3>为什么单独一个类，不塞进 {@link MxfSpecialEvents}</h3>
 * <p>{@code MxfSpecialEvents} 的语义是「SECTION 名 → 特殊事件」识别器，它的
 * {@code normalizeSection} 会剥离末尾的数字后缀，{@code eventNames()} 又被既有测试当成
 * 「8 类特殊事件」在用。日报的 19 类是对 {@code data_slice.event_name} 的<b>展示分组</b>，
 * 两者口径不同，混在一起会让两边都说不清。</p>
 *
 * <h3>三个必须守住的规则（改之前先读）</h3>
 * <ol>
 *   <li><b>顺序即条形图顺序</b>：{@link #keySet()} 用 {@link LinkedHashMap}，前端按索引取色，
 *       增删成员会让 19 色错位。</li>
 *   <li><b>前缀匹配只给第 7 项（APA）</b>：库里存在 {@code apa_all_time_event_segment} 这类
 *       带后缀的值，而配置里的 filter 叫 {@code apa_all_time_event}。若对所有桶都用
 *       {@code startsWith}，{@code dssad_collision} 会把 {@code dssad_collision_locked} 吞掉。</li>
 *   <li><b>一条切片只落一个桶</b>：{@link #classify(String)} 是纯函数、单值返回，于是
 *       {@code Σ(19 桶) + 未归类 == 事件总数} 恒成立；未归类由调用方用减法算，
 *       不需要在 SQL 里再排除一遍 {@code obf_issue} / {@code obf_metric} / 空事件名。</li>
 * </ol>
 *
 * <h3>与原型 / 需求表的对应关系</h3>
 * <pre>
 *   原型写 highbeam_on_filter → 这不是 event_name，是筛选器名；
 *                              实际库值是 high_highbeam_on + mid_highbeam_on，两值同桶求和
 *   原型写 fusion_aeb         → 库值是 fushion_aeb（双 u）。2026-09-18 已定案保持该拼写，
 *                              V28 迁移里还有 fusion_aeb → fushion_aeb 的反向兜底 UPDATE。
 *                              ⚠️ 因此这里<b>刻意不登记</b> fusion_aeb，别再"顺手纠正拼写"
 *   原型写 cp_less_mng_event  → 全仓零命中，实际值是 cp_ess_trig_event（ESS 触发）
 * </pre>
 *
 * @feature: mxf
 */
public final class MxfDailyReportEvents {

    private MxfDailyReportEvents() {
    }

    /** 手动远光打点的合并桶 key：库里是 high_highbeam_on / mid_highbeam_on 两个事件。 */
    public static final String BUCKET_HIGHBEAM = "highbeam_on";

    /** APA 全量的合并桶 key：库里可能存在 {@code apa_all_time_event_segment} 等后缀变体。 */
    public static final String BUCKET_APA = "apa_all_time_event";

    private static final String APA_PREFIX = "apa_all_time_event";

    /** 19 个桶（桶 key → 展示标题），插入顺序即条形图从上到下的顺序。 */
    private static final Map<String, String> BUCKETS;

    /** 原始 event_name（小写）→ 桶 key。只登记精确值，含大量 identity 映射。 */
    private static final Map<String, String> RAW_TO_BUCKET;

    static {
        Map<String, String> buckets = new LinkedHashMap<>(32);
        buckets.put("imu_collision",           "IMU碰撞风险");
        buckets.put("system_crash",            "系统崩溃");
        buckets.put(BUCKET_HIGHBEAM,           "手动远光打点");
        buckets.put("fushion_aeb",             "AEB触发风险");
        buckets.put("cp_aes_trig_event",       "AES触发");
        buckets.put("cp_ess_trig_event",       "ESS触发");
        buckets.put(BUCKET_APA,                "APA全量");
        buckets.put("apa_simplified_event",    "APA触发");
        buckets.put("meb_brake_event",         "LAEB触发");
        buckets.put("lpnp_emergency_event",    "AVP接管");
        buckets.put("lpnp_takeover",           "AVP退出");
        buckets.put("rads_takeover",           "LRAS退出");
        buckets.put("rma_collision_event",     "RMA碰撞");
        buckets.put("nsp_abnormal_event",      "NSP退出");
        buckets.put("general_collision_event", "NP碰撞");
        buckets.put("unp_tkov_colli",          "UNP碰撞");
        buckets.put("dssad_collision",         "DSSAD触发");
        buckets.put("dssad_collision_locked",  "DSSAD触发(锁定)");
        buckets.put("dbw_disabled_takeover",   "NOA接管");
        BUCKETS = Collections.unmodifiableMap(buckets);

        Map<String, String> raw = new LinkedHashMap<>(64);
        // 1) identity：桶 key 本身就是一个真实 event_name
        for (String key : buckets.keySet()) {
            if (!BUCKET_HIGHBEAM.equals(key)) {
                raw.put(key, key);
            }
        }
        // 2) 合并桶：一个 UI 类目对应多个 event_name
        raw.put("high_highbeam_on", "highbeam_on");
        raw.put("mid_highbeam_on", "highbeam_on");
        // 兜底：若某些历史数据的 event_name 直接落了筛选器名（正常不该发生，但存在就不该漏统计）
        raw.put("highbeam_on_filter", "highbeam_on");
        raw.put("apa_all_time_event_segment", BUCKET_APA);
        RAW_TO_BUCKET = Collections.unmodifiableMap(raw);
    }

    /**
     * 19 个桶 key（不可变、顺序固定）。前端按此顺序渲染条形图并用同序色板取色。
     */
    public static List<String> keySet() {
        return List.copyOf(BUCKETS.keySet());
    }

    /**
     * 桶 key → 展示标题；未知 key 原样返回。
     */
    public static String titleOf(String bucketKey) {
        if (bucketKey == null) {
            return null;
        }
        String title = BUCKETS.get(bucketKey);
        return title == null ? bucketKey : title;
    }

    /**
     * 原始 {@code data_slice.event_name} → 桶 key。
     *
     * <p>判定顺序：① 精确映射优先（保证 {@code dssad_collision_locked} 不被
     * {@code dssad_collision} 吞掉）→ ② 仅对 APA 开前缀匹配 → ③ 其余一律未归类。</p>
     *
     * @return 桶 key；null / 空白 / 不在 19 类内时返回 {@code null}（由调用方计入「其他」）
     */
    public static String classify(String rawEventName) {
        if (rawEventName == null) {
            return null;
        }
        String key = rawEventName.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        String exact = RAW_TO_BUCKET.get(key);
        if (exact != null) {
            return exact;
        }
        // 只给 APA 开前缀：apa_all_time_event / apa_all_time_event_segment / ..._xxx 都归同一桶。
        // apa_simplified_event 前缀不同，不会被误吞。
        if (key.startsWith(APA_PREFIX)) {
            return BUCKET_APA;
        }
        return null;
    }
}
