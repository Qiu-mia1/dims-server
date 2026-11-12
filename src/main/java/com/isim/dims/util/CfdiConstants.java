package com.isim.dims.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CFDI 模块共享常量
 *
 * <p>集中管理 H265 传感器映射表、Topic→CFDI ID 映射表及相关工具方法，
 * 避免在多个 ServiceImpl 中重复定义。</p>
 *
 * <p>命名体系映射（来自 HANDOFF-CFDI-VERIFICATION.md Section 4.2）：</p>
 * <pre>
 *   Bag Topic（/sensor/camera_front_far/video）
 *       → extractSensorNameFromTopic() → "camera_front_far"
 *       → CAMERA_NAME_TO_CFDI_ID      → "F30_front_far"（CFDI sensor ID / sub_span_us key / h265 文件名前缀）
 * </pre>
 * @feature: infra, cfdi
 */
public final class CfdiConstants {

    private CfdiConstants() {
        // 工具类，禁止实例化
    }

    /** h265 文件名前缀 → sensor_name 映射表（双向一致，key == value） */
    public static final Map<String, String> H265_SENSOR_MAP;

    /** h265 文件名前缀列表 */
    public static final String[] H265_SENSOR_PREFIXES_ARRAY;

    /** h265 文件名前缀集合 */
    public static final Set<String> H265_SENSOR_PREFIXES;

    /**
     * Bag Topic 中提取的 sensor_name → CFDI sensor ID 映射
     *
     * <p>Bag 中的 topic 使用 camera 名称命名（如 camera_front_far），
     * 而 CFDI 元数据（sub_span_us）和 h265 文件前缀使用 CFDI ID（如 F30_front_far）。
     * 此映射桥接两套命名体系。</p>
     *
     * <p>参见 HANDOFF-CFDI-VERIFICATION.md Section 4.2 对应关系表。</p>
     */
    public static final Map<String, String> CAMERA_NAME_TO_CFDI_ID;

    static {
        // ---- H265 传感器映射（文件名前缀 → sensor_name） ----
        Map<String, String> h265Map = new LinkedHashMap<>();
        h265Map.put("F30_front_far", "F30_front_far");
        h265Map.put("F50_rear_mid", "F50_rear_mid");
        h265Map.put("F100_front_wide", "F100_front_wide");
        h265Map.put("F100_left_front", "F100_left_front");
        h265Map.put("F100_left_rear", "F100_left_rear");
        h265Map.put("F100_right_front", "F100_right_front");
        h265Map.put("F100_right_rear", "F100_right_rear");
        h265Map.put("F195_front_fisheye", "F195_front_fisheye");
        h265Map.put("F195_left_fisheye", "F195_left_fisheye");
        h265Map.put("F195_rear_fisheye", "F195_rear_fisheye");
        h265Map.put("F195_right_fisheye", "F195_right_fisheye");
        h265Map.put("latch", "latch");
        H265_SENSOR_MAP = Collections.unmodifiableMap(h265Map);
        H265_SENSOR_PREFIXES_ARRAY = h265Map.keySet().toArray(new String[0]);
        H265_SENSOR_PREFIXES = Collections.unmodifiableSet(h265Map.keySet());

        // ---- Topic camera_name → CFDI sensor ID 映射 ----
        Map<String, String> cameraMap = new LinkedHashMap<>();
        cameraMap.put("camera_front_far", "F30_front_far");
        cameraMap.put("camera_rear_mid", "F50_rear_mid");
        cameraMap.put("camera_front_wide", "F100_front_wide");
        cameraMap.put("camera_left_front", "F100_left_front");
        cameraMap.put("camera_left_rear", "F100_left_rear");
        cameraMap.put("camera_right_front", "F100_right_front");
        cameraMap.put("camera_right_rear", "F100_right_rear");
        cameraMap.put("camera_front_fisheye", "F195_front_fisheye");
        cameraMap.put("camera_left_fisheye", "F195_left_fisheye");
        cameraMap.put("camera_rear_fisheye", "F195_rear_fisheye");
        cameraMap.put("camera_right_fisheye", "F195_right_fisheye");
        // latch — 特殊 topic，非 /sensor/ 格式
        cameraMap.put("body_report", "latch");
        CAMERA_NAME_TO_CFDI_ID = Collections.unmodifiableMap(cameraMap);
    }

    /**
     * 从 Bag Topic 中提取 sensor_name
     *
     * <p>处理两种 topic 格式：</p>
     * <ul>
     *   <li>{@code /sensor/{camera_name}/video} → {@code camera_name}</li>
     *   <li>{@code /vehicle/body_report} → {@code body_report}</li>
     * </ul>
     *
     * @param topic Bag Topic 字符串
     * @return 提取的 sensor_name，若无法识别则返回 null
     */
    public static String extractSensorNameFromTopic(String topic) {
        if (topic == null || topic.isEmpty()) return null;
        String t = topic.toLowerCase();
        // /sensor/{camera_name}/video → camera_name
        if (t.startsWith("/sensor/") && t.endsWith("/video")) {
            // "/sensor/" 长度 = 8, "/video" 长度 = 6
            return t.substring(8, t.length() - 6);
        }
        // /vehicle/body_report → body_report
        if ("/vehicle/body_report".equals(t)) {
            return "body_report";
        }
        return null;
    }

    /**
     * 从 Bag Topic 获取 CFDI sensor ID
     *
     * <p>先通过 {@link #extractSensorNameFromTopic(String)} 提取 sensor_name，
     * 再通过 {@link #CAMERA_NAME_TO_CFDI_ID} 映射到 CFDI sensor ID。</p>
     *
     * @param topic Bag Topic 字符串
     * @return CFDI sensor ID（如 "F30_front_far"），若无法识别则返回 null
     */
    public static String getCfdiSensorIdFromTopic(String topic) {
        String sensorName = extractSensorNameFromTopic(topic);
        if (sensorName == null) return null;
        return CAMERA_NAME_TO_CFDI_ID.get(sensorName);
    }
}
