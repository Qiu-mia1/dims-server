package com.isim.dims.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MXF 事件识别（SECTION 名 → 事件名）与事件名 → 看板展示标题（中文枚举值）。
 *
 * <h3>SECTION 名以接口定义为准</h3>
 * <p>SECTION 名一律以 {@code MXF埋点数据开放接口定义_0911.xlsx} 的「表名称」列为准
 * （车辆运行 2 张 + 智驾运行 21 张 + 故障诊断 1 张 + ADAS 12 张 + AEB 18 张，共 54 张表）。
 * 示例 {@code .data} 文件只覆盖其中少数几张表（如 RUNNING-INFO / AUTO-INFO /
 * MD-NODE-STATUS-INFO-END），<b>不能</b>拿它来推断事件识别规则。
 *
 * <h3>为什么不能从 meta 取</h3>
 * <p>实测 MXF 数据包的 meta 文件里<b>没有</b> {@code event_name} 字段，只有
 * {@code vin / car_type / sub_car_type / trigger_time / begin_time / end_time /
 * data_size / compress_type / size_unit / related_tag / kafka_bag_name / system_software_version}。
 * 因此事件类型只能从 {@code .data} 日志行的 SECTION 名识别：
 * <pre>
 *   [2026-09-15 00:27:53.871608208]:[RUNNING-INFO]:{"auto_dis_since_power_on_(m)":12345, ...}
 *                                    ^^^^^^^^^^^^ section 名
 * </pre>
 *
 * <h3>匹配规则</h3>
 * <p>先把 section 名归一化（转小写、所有非字母数字字符统一成 {@code _}、
 * 按 Change Log V1.0.6 剥离末尾的 {@code -数字}），再与 {@link #EVENT_TITLES} 键集
 * 及其别名比对，因此 {@code DSSAD-COLLISION}、{@code dssad_collision}、{@code DSSAD.COLLISION}
 * 都会识别为 {@code dssad_collision}；{@code AEB-ACTIVATE-2} 与 {@code AEB-ACTIVATE} 同一条。
 *
 * <h3>事件 ↔ 0911 定义的表名称</h3>
 * <pre>
 *   cp_aes_trig_event  ←  AES-ACTIVATE   AES触发(Automatic Emergency Steering)
 *   cp_ess_trig_event  ←  ESS-ACTIVATE   ESS触发(Emergency Steering Support)
 *   fushion_aeb        ←  AEB-ACTIVATE   AEB触发风险
 *
 *   dssad_collision         ┐
 *   dssad_collision_locked  │
 *   imu_collision           ├ 0911 定义中没有对应表，按同名 SECTION 命中
 *   unp_tkov_colli          │
 *   system_crash            ┘
 * </pre>
 *
 * <p>若 MMT 侧改用别的 section 名落地特殊事件，只需在 {@link #SECTION_ALIASES} 里登记一行，
 * 无需改任何统计逻辑。
 *
 * <h3>事件名 → 中文枚举值的映射</h3>
 * <p>数据源：{@code CX835-MMT-筛选器.xlsx}（《筛选器》sheet 的「事件名」/「枚举」两列）。
 * Excel「枚举」列空白的（如 {@code obf_metric} / {@code obf_issue}）不在
 * {@link #EVENT_TITLES} 中登记，{@link #titleOf(String)} 会原样返回事件名；前端
 * {@code dims-web/src/utils/eventTitleMap.js#getEventTitle} 同语义。
 * 例外：{@code apa_all_time_event_segment} 枚举列虽空白，需求方指定显示「APA全量」，已登记。
 *
 * <h3>数据来源</h3>
 * <p>事件名与 {@code dims-server/src/main/resources/config/camera_event_config.json} 中
 * {@code events.*.priority == "special"} 的条目保持一致；对应的 MXF SECTION 名取自 0911 接口定义。
 *
 * @feature: mxf
 */
public final class MxfSpecialEvents {

    private MxfSpecialEvents() {
    }

    /**
     * 事件名 → 看板展示标题（即「枚举」中文值）。
     *
     * <p>数据源：{@code CX835-MMT-筛选器.xlsx}（《筛选器》sheet 的「事件名」/「枚举」两列）。
     * Excel 中「枚举」列空白的项不在此登记，由 {@link #titleOf(String)} 走 fallback 原样输出。
     * 维护：Excel 改表 → 同步改本类 → 同步改前端 {@code dims-web/src/utils/eventTitleMap.js}。
     *
     * <p>使用 {@link LinkedHashMap}：<b>插入顺序即并列时的优先级</b>
     * （同一个包里多个特殊事件记录数相同时，取靠前的那个作为主事件）。
     */
    private static final Map<String, String> EVENT_TITLES;

    /**
     * section 别名（归一化后的 SECTION 名 → 事件名）。
     *
     * <p>只登记 0911 接口定义里「表名称」与 8 类事件名不一致的条目；
     * 名称本身就是事件名的（如 {@code DSSAD-COLLISION}）由
     * {@link #EVENT_TITLES} 直接命中，无需在此登记。
     */
    private static final Map<String, String> SECTION_ALIASES;

    static {
        // === 中文展示名的出处：CX835-MMT-筛选器.xlsx「事件名 / 枚举」两列 ===
        //   Excel「枚举」列空白的（如 obf_metric / obf_issue）不在此登记，
        //   titleOf 会原样返回事件名（与前端 getEventTitle 一致）。
        //   例外：apa_all_time_event_segment 枚举列空白但需求方指定「APA全量」，已登记。
        //
        // === 事件名拼写：`fushion_aeb`（f-u-s-h-i-o-n，不是 f-u-s-i-o-n）===
        //   ⚠️ 以**平台侧 camera_event_config.json 的原始 key** 为准，就是 `fushion_aeb`。
        //   2026-09-17 曾按需求方口头反馈改名为 `fusion_aeb`（含 key / filter / 前端标题表 / V28 迁移），
        //   2026-09-18 需求方复核后**撤销该改名**，全部恢复 `fushion_aeb`。
        //   → 不要再"顺手纠正拼写"，改名会同时废掉已入库的 event_name 与前端映射。
        Map<String, String> titles = new LinkedHashMap<>(32);
        titles.put("imu_collision",           "IMU碰撞");
        titles.put("system_crash",            "系统崩溃");
        titles.put("high_highbeam_on",        "打点");
        titles.put("mid_highbeam_on",         "打点");
        titles.put("fushion_aeb",             "AEB触发风险");
        titles.put("cp_aes_trig_event",       "AES触发");
        titles.put("cp_ess_trig_event",       "ESS触发");
        // Excel「枚举」列虽为空，需求方指定显示「APA全量」，故在此登记（不走 fallback 显示事件名）
        titles.put("apa_all_time_event_segment", "APA全量");
        titles.put("apa_simplified_event",    "APA触发");
        titles.put("meb_brake_event",         "LAEB触发");
        titles.put("lpnp_emergency_event",    "AVP接管");
        titles.put("lpnp_takeover",           "AVP退出");
        titles.put("rads_takeover",           "LRAS退出");
        titles.put("rma_collision_event",     "RMA碰撞");
        titles.put("nsp_abnormal_event",      "NSP退出");
        titles.put("general_collision_event", "NP碰撞");
        titles.put("unp_tkov_colli",          "UNP碰撞");
        titles.put("dbw_disabled_takeover",   "NOA接管");
        // 恒润（Hirain）触发事件：2026-11-02 版定义，事件名统一带优先级前缀 P0/P1/P2
        // （旧版无前缀，如 L2_ID_01 / NOA_ID_9，已按新定义改名；中文展示名不变）
        // L2 事件
        titles.put("P0_L2_ID_01",   "FCW触发报警");
        titles.put("P0_L2_ID_02",   "AEB触发制动");
        titles.put("P0_L2_ID_03",   "驾驶员紧急制动");
        titles.put("P0_L2_ID_04",   "发生碰撞");
        // NOA 事件
        titles.put("P0_NOA_ID_01",  "NOA紧急制动");
        titles.put("P0_NOA_ID_02",  "紧急转向-非路口");
        titles.put("P0_NOA_ID_03",  "紧急转向-路口");
        titles.put("P0_NOA_ID_04",  "主动接管-执行制动");
        titles.put("P2_NOA_ID_05",  "主动接管-猛踩油门");
        titles.put("P0_NOA_ID_06",  "主动接管-猛打方向盘");
        titles.put("P1_NOA_ID_07",  "功能降级-感知原因");
        titles.put("P2_NOA_ID_08",  "功能降级-非感知原因");
        titles.put("P2_NOA_ID_09",  "主动接管-非紧急制动");
        titles.put("P2_NOA_ID_10",  "LCC/NOA无法激活");
        titles.put("P0_NOA_ID_11",  "LCC主动接管-猛打方向盘");
        titles.put("P0_NOA_ID_12",  "LCC主动接管-执行制动");
        titles.put("P2_NOA_ID_13",  "方向盘异常抖动");
        titles.put("P2_NOA_ID_14",  "控车距车道线过近");
        // 泊车事件
        titles.put("P0_Park_ID_00", "泊车碰撞风险");
        titles.put("P2_Park_ID_01", "泊车异常退出");
        titles.put("P2_Park_ID_02", "AVP路线复刻-倒车绕障");
        titles.put("P2_Park_ID_03", "AVP路线复刻-偏离路线");
        titles.put("P2_Park_ID_04", "AVP路线复刻-窄道通行");
        titles.put("P2_Park_ID_05", "AVP路线复刻-复杂路口");
        titles.put("P2_Park_ID_06", "APA泊入-路径规划失败");
        titles.put("P2_Park_ID_07", "APA泊入-障碍物卡停");
        titles.put("P2_Park_ID_08", "APA泊入-终点偏差大");
        titles.put("P0_Park_ID_09", "LAEB触发");
        // DSSAD：0911 定义里没有对应表，靠同名 SECTION 命中（见类注释）。
        // 之前这两个 key 没登记 → resolveEvent("DSSAD-COLLISION") 返回 null、titleOf 原样吐英文，
        // 日报看板的 19 类里第 17/18 项只能显示 event_name，故补登记。
        // ⚠️ 必须追加在表尾：EVENT_TITLES 的插入顺序 = 包内多事件并列时的主事件优先级
        //    （MxfImportServiceImpl 用 priorityOf 挑主事件），插在中间会改掉 L2/NOA/Park 的既有优先级。
        titles.put("dssad_collision", "DSSAD触发");
        titles.put("dssad_collision_locked", "DSSAD触发(锁定)");
        EVENT_TITLES = Collections.unmodifiableMap(titles);

        // 0911 接口定义「表名称」→ 事件名（名称与事件名不同的才需要登记）
        Map<String, String> aliases = new LinkedHashMap<>(8);
        aliases.put("aes_activate", "cp_aes_trig_event");   // ADAS：AES触发
        aliases.put("ess_activate", "cp_ess_trig_event");   // ADAS：ESS触发
        aliases.put("aeb_activate", "fushion_aeb");         // AEB：AEB触发风险
        SECTION_ALIASES = Collections.unmodifiableMap(aliases);
    }

    /**
     * section 名归一化：转小写，所有非字母数字字符统一成 {@code _}，
     * 并按 0911 定义 Change Log V1.0.6 去掉末尾的 {@code -{数字}}
     * （{@code AEB-ACTIVATE-2} → {@code aeb_activate}）。
     *
     * @return 归一化结果；入参为 null / 空白时返回 null
     */
    public static String normalizeSection(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == '_') {
            len--;
        }
        // V1.0.6：末尾的 "-{数字}" 整体去掉；只有确实存在 "_数字" 后缀时才剥离，
        // 避免把纯数字 section 名（如 "123"）整段吃掉
        int digitStart = len;
        while (digitStart > 0 && isDigit(sb.charAt(digitStart - 1))) {
            digitStart--;
        }
        if (digitStart < len && digitStart > 0 && sb.charAt(digitStart - 1) == '_') {
            len = digitStart - 1;
            while (len > 0 && sb.charAt(len - 1) == '_') {
                len--;
            }
        }
        return len == 0 ? null : sb.substring(0, len);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * SECTION 名 → 8 类特殊事件名。
     *
     * @return 事件名（如 {@code dssad_collision}）；不属于 8 类特殊事件时返回 null
     */
    public static String resolveEvent(String section) {
        String key = normalizeSection(section);
        if (key == null) {
            return null;
        }
        if (EVENT_TITLES.containsKey(key)) {
            return key;
        }
        return SECTION_ALIASES.get(key);
    }

    /**
     * SECTION 是否属于 8 类特殊事件。
     */
    public static boolean isSpecialSection(String section) {
        return resolveEvent(section) != null;
    }

    /**
     * 事件名 → 看板展示标题（未知事件原样返回）。
     */
    public static String titleOf(String eventName) {
        if (eventName == null) {
            return null;
        }
        String title = EVENT_TITLES.get(eventName);
        return title == null ? eventName : title;
    }

    /**
     * 事件名集合（不可变，顺序即优先级）。
     */
    public static Set<String> eventNames() {
        return EVENT_TITLES.keySet();
    }

    /**
     * 事件优先级（越小越高）；未知事件返回 {@link Integer#MAX_VALUE}。
     */
    public static int priorityOf(String eventName) {
        if (eventName == null) {
            return Integer.MAX_VALUE;
        }
        int i = 0;
        for (String name : EVENT_TITLES.keySet()) {
            if (name.equals(eventName)) {
                return i;
            }
            i++;
        }
        return Integer.MAX_VALUE;
    }
}
