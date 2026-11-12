package com.isim.dims.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MXF 看板「事件分布」口径：0911 接口定义的「表名称 → 表含义」映射。
 *
 * <h3>口径</h3>
 * <p>事件分布不再使用 {@code camera_event_config.json} 里的自定义事件名，
 * 直接以 {@code MXF埋点数据开放接口定义_0911.xlsx} 五个分表的「表含义」列作为展示名，
 * 触发次数 = 各包 {@code mxf_packet.section_stats.sections} 中归一化后与该表「表名称」
 * （{@link MxfSpecialEvents#normalizeSection}）一致的 SECTION 次数之和。
 *
 * <h3>已剔除「开启记录」</h3>
 * <p>「表含义」含 {@code 开启记录} 的功能开启类表（如 AUTO-INFO / ACC-INFO / PILOT-INFO /
 * V2-*-INFO 等，共 18 张）不计入事件分布，仅保留运行概览、
 * 导航旅程、智驾请求、变道、MRM、故障码与 ADAS/AEB 触发类表，共 36 张。
 *
 * <p>顺序与 0911 定义一致（车辆运行 → 智驾运行 → 故障诊断 → ADAS → AEB），
 * 并列时由调用方按次数稳定排序。
 *
 * @feature: mxf
 */
public final class MxfEventTables {

    private MxfEventTables() {
    }

    /**
     * 事件分布展示表：表名称 → 表含义（插入顺序即 0911 定义顺序）。
     */
    private static final Map<String, String> TABLE_MEANINGS;

    static {
        Map<String, String> tables = new LinkedHashMap<>(64);
        // ----- 车辆运行 -----
        tables.put("RUNNING-INFO", "运行概览，每5s记录1次");
        tables.put("NAVI-TRIP", "导航旅程，导航退出时刻记录");
        // ----- 智驾运行 -----
        tables.put("CMD-RECEIVED", "每次用户发起智驾请求（e.g.拨杆）记录");
        tables.put("LANE-CHANGE-INFO", "每次NP变道退出后记录");
        tables.put("MRM-INFO", "MRM触发记录");
        // ----- 故障诊断 -----
        tables.put("MD-NODE-STATUS-INFO-END", "故障码触发记录");
        // ----- ADAS -----
        tables.put("LDW-ACTIVATE", "LDW触发");
        tables.put("LKA-ACTIVATE", "LKA(LDP)触发");
        tables.put("ELK-RE-ACTIVATE", "ELK触发（路沿）");
        tables.put("ELK-OC-ACTIVATE", "ELK触发（对向车）");
        tables.put("ELK-OT-ACTIVATE", "ELK触发（同向车）");
        tables.put("CMSR-ACTIVATE", "RCW触发");
        tables.put("SLIF-ACTIVATE", "SLIF触发");
        tables.put("IHC-ACTIVATE", "IHC(Intelligent High-Beam Control)触发");
        tables.put("ADB-ACTIVATE", "ADB触发(Adaptive Driving Beam)");
        tables.put("AELK-ACTIVATE", "AELK触发（Autonomous ELK）");
        tables.put("ESS-ACTIVATE", "ESS触发(Emergency Steering Support)");
        tables.put("AES-ACTIVATE", "AES触发(Automatic Emergency Steering)");
        // ----- AEB -----
        tables.put("CDW-ACTIVATE", "CDW触发(Close Distance Warning)");
        tables.put("FCW-ACTIVATE", "FCW触发");
        tables.put("AWB-ACTIVATE", "AWB触发(Automatic Warning Brake)");
        tables.put("AEB-PB-ACTIVATE", "AEB(Partial Brake)触发");
        tables.put("AEB-ACTIVATE", "AEB触发");
        tables.put("AEB-LIM-ACTIVATE", "AEB(limiter)触发");
        tables.put("AEB-EH-ACTIVATE", "AEB触发(Emergence Hold)");
        tables.put("EBA-ACTIVATE", "EBA触发(Electronic Brake Assist)");
        tables.put("FCTA-ACTIVATE", "FCTA触发");
        tables.put("FCTB-ACTIVATE", "FCTB触发");
        tables.put("RCTA-ACTIVATE", "RCTA触发");
        tables.put("RCTB-ACTIVATE", "RCTB触发");
        tables.put("MSP-WARN-ACTIVATE", "MSP-WARN触发");
        tables.put("MSP-BRAKE-ACTIVATE", "MSP-BRAKE触发");
        tables.put("RVB-WARN-ACTIVATE", "RVB-WARN触发(Rear Virtual Bumper)");
        tables.put("RVB-BRAKE-ACTIVATE", "RVB-BRAKE触发(Rear Virtual Bumper)");
        tables.put("AMAP-BRAKE-ACTIVATE", "AMAP_BRAKE触发");
        tables.put("AMAP-LIMIT-ACTIVATE", "AMAP_LIMIT触发");
        TABLE_MEANINGS = Collections.unmodifiableMap(tables);
    }

    /**
     * 事件分布全部表（表名称 → 表含义），不可变。
     */
    public static Map<String, String> tables() {
        return TABLE_MEANINGS;
    }

    /**
     * 表名称 → 表含义；未登记时返回 null。
     */
    public static String meaningOf(String tableName) {
        return tableName == null ? null : TABLE_MEANINGS.get(tableName);
    }
}
