package com.isim.dims.util;

/**
 * 回放 topic 工具
 *
 * <p>v1.4 修正（P1 数据实测结论）：/momenta_public/* 与 /navigation/* 共 8 个 topic
 * 并非「仅录 Header 无负载」——实测（消息级反序列化）所有消息的 frame_id 字段内嵌
 * 完整 JSON 业务负载：smooth_object 融合感知目标（10Hz）、ddld landmark_vis 地图
 * （8.5Hz）、tflr_tsr_vis、aeb_state、auto_state、parking_slot_info、
 * obstacle_smooth_for_display、navigation 导航信息等。因此：
 * <ul>
 *   <li>不再按 topic 前缀一刀切排除（has_payload 一律 true，Raw 消息接口不再 422）；</li>
 *   <li>本类保留 topic 数据分类（data_category），供 context 标记感知/地图等数据类别。</li>
 * </ul>
 * @feature: infra, replay
 */
public final class ReplayTopicUtil {

    private ReplayTopicUtil() {
    }

    /**
     * 感知 topic：/momenta_public/perception/*（frame_id 内嵌融合感知 JSON，
     * 如 smooth_object 融合感知目标）
     */
    public static boolean isPerceptionTopic(String topic) {
        return topic != null && topic.startsWith("/momenta_public/perception/");
    }

    /**
     * 融合感知目标 topic：同时满足 isPerceptionTopic 且含 smooth_object。
     *
     * <p>白名单扩充后 /momenta_public/perception/* 下混入 vision/lidar/fusion
     * 等多种 schema 的 topic，但 extract_perception.py 只解析 smooth_object 的
     * perception_fusion_objects_data 字段——其他 schema 会静默产出空 objects 帧，
     * 污染 perception 缓存。默认提取集合必须只包含 smooth_object。</p>
     */
    public static boolean isPerceptionObjectTopic(String topic) {
        return isPerceptionTopic(topic) && topic.contains("smooth_object");
    }

    /**
     * 地图 topic：/momenta_public/ddld/*（frame_id 内嵌车道线/道路边界/停止线
     * 等 ddld 地图数据，如 landmark_vis）
     */
    public static boolean isMapTopic(String topic) {
        return topic != null && topic.startsWith("/momenta_public/ddld/");
    }

    /**
     * topic 数据分类（契约 ReplayTopicInfo.data_category 枚举）：
     * <ul>
     *   <li>perception — /momenta_public/perception/*（融合感知）</li>
     *   <li>map — /momenta_public/ddld/*（ddld 地图）</li>
     *   <li>navigation — /navigation/*（导航信息）</li>
     *   <li>sensor — 其余传感器 topic（相机/雷达/超声波/GNSS-IMU/车况）</li>
     *   <li>hdmap — 高精地图（/mla/*、/hdmap/*、/map_provider/*、/map_manager/*）</li>
     *   <li>planning — 规划（/hnp_planning/*、/unp_planning/*、/cp_planning/*、
     *       /planning/*、/pnp_planning/*、/mrm_planning/*、/msd/planning/*）</li>
     *   <li>prediction — 预测（/msd/prediction/*）</li>
     *   <li>telemetry — 遥测（/mff/*）</li>
     *   <li>vehicle — 整车（/controller/*、/vehicle_service/*、/vehicle/*、
     *       /mcu/*、/control/*）</li>
     *   <li>worldmodel — 世界模型（/worldmodel/*）</li>
     *   <li>location — 定位（/localization/*、/lpnpmapping/*）</li>
     *   <li>routing — 路由（/routing/*、/navigation/*）</li>
     *   <li>other — 未归类的其他 topic（aeb_state/auto_state 等）</li>
     * </ul>
     *
     * <p>匹配规则：保留原有 5 个分类判断不变（优先级最高）；新分类判断前先
     * strip /momenta_public 前缀，使 bag 原始 topic 名与转换后名称同时命中。</p>
     */
    public static String dataCategory(String topic) {
        if (topic == null) {
            return "other";
        }
        if (isPerceptionTopic(topic)) {
            return "perception";
        }
        if (isMapTopic(topic)) {
            return "map";
        }
        if (topic.startsWith("/navigation/")) {
            return "navigation";
        }
        // /perception/fusion/* → perception（CFDI 转换后去掉了 /momenta_public 前缀）
        // /perception/radar/* → sensor（雷达数据，非融合感知）
        // /sensor/* → sensor
        if (topic.startsWith("/perception/fusion/")) {
            return "perception";
        }
        if (topic.startsWith("/sensor/") || topic.startsWith("/perception/radar/")) {
            return "sensor";
        }
        String t = stripMomentaPrefix(topic);
        // hdmap
        if (t.startsWith("/mla/") || t.startsWith("/hdmap/") || t.startsWith("/map_provider/")
                || t.startsWith("/map_manager/")) {
            return "hdmap";
        }
        // planning（含 /msd/planning/*，与 prediction 的 /msd/prediction/* 前缀互斥）
        if (t.startsWith("/hnp_planning/") || t.startsWith("/unp_planning/")
                || t.startsWith("/cp_planning/") || t.startsWith("/planning/")
                || t.startsWith("/pnp_planning/") || t.startsWith("/mrm_planning/")
                || t.startsWith("/msd/planning/")) {
            return "planning";
        }
        // prediction
        if (t.startsWith("/msd/prediction/")) {
            return "prediction";
        }
        // telemetry
        if (t.startsWith("/mff/")) {
            return "telemetry";
        }
        // vehicle
        if (t.startsWith("/controller/") || t.startsWith("/vehicle_service/")
                || t.startsWith("/vehicle/") || t.startsWith("/mcu/") || t.startsWith("/control/")) {
            return "vehicle";
        }
        // worldmodel
        if (t.startsWith("/worldmodel/")) {
            return "worldmodel";
        }
        // location
        if (t.startsWith("/localization/") || t.startsWith("/lpnpmapping/")) {
            return "location";
        }
        // routing（原始 /navigation/* 已在上方命中 navigation，此处兜底转换后名称）
        if (t.startsWith("/routing/") || t.startsWith("/navigation/")) {
            return "routing";
        }
        return "other";
    }

    /**
     * 去除 /momenta_public 前缀，返回可在原始 topic 名上匹配的前缀形式。
     * 例：/momenta_public/hnp_planning/info → /hnp_planning/info；
     * 原始名 /hnp_planning/info 原样返回。
     */
    private static String stripMomentaPrefix(String topic) {
        if (topic.startsWith("/momenta_public/")) {
            return topic.substring("/momenta_public".length());
        }
        return topic;
    }
}
