package com.isim.dims.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.util.MxfSpecialEvents;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CX835-MMT 摄像头/事件配置（来源：CX835-MMT-筛选器-摄像头数据.xlsx 转换的 JSON 配置）
 *
 * <p>配置文件：classpath:config/camera_event_config.json。</p>
 * <ul>
 *   <li><b>cameras</b>：CFDI sensor ID → 摄像头通道（topic / 中文名 / 分辨率）。
 *       分辨率策略：前视摄像头（前视广角 F100_front_wide、前视远焦 F30_front_far）1080P，其余 720P。</li>
 *   <li><b>events</b>：触发类型（meta.event_name，即 LocalDirectoryImportServiceImpl#handleSession
 *       解析的 ref.metaInfo.getEventName()）→ 该事件需要转换的摄像头路列表。</li>
 *   <li><b>点云默认不转换</b>：本配置仅包含摄像头通道，视频转换流水线只处理配置内的 camera topic。</li>
 * </ul>
 *
 * <p>event_name 未在配置中（未知/缺省）时按 fail-open 处理：返回全部摄像头，行为与历史版本一致。</p>
 *
 * @feature: ingestion, visualization
 */
@Slf4j
@Component
public class CameraEventConfig {

    private static final String CONFIG_PATH = "config/camera_event_config.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * CFDI sensor ID → 摄像头通道
     */
    private Map<String, CameraChannel> cameras = new LinkedHashMap<>();

    /**
     * 事件名（event_name）→ 事件配置
     */
    private Map<String, EventEntry> events = new LinkedHashMap<>();

    /**
     * topic / camera_name → 摄像头通道（反查索引）
     */
    private Map<String, CameraChannel> cameraIndex = new LinkedHashMap<>();

    /**
     * 归一化 SECTION 名（事件名 / 筛选器名）→ 事件名列表（反查索引）。
     *
     * <p>同一个 key 可能对应多个事件（如 {@code highbeam_on_filter} 同时对应
     * {@code high_highbeam_on} / {@code mid_highbeam_on}），此时无法仅凭
     * SECTION 名区分，统计时各计入一次。
     */
    private Map<String, List<String>> sectionEventIndex = new LinkedHashMap<>();

    /**
     * 筛选器名（{@code events.*.filter}）→ 事件名列表（反查索引，保持配置顺序）。
     *
     * <p>{@code filter} 缺失或空白时回退用事件名本身作为键。一个筛选器可能对应多个事件
     * （如 {@code highbeam_on_filter} → {@code high_highbeam_on} + {@code mid_highbeam_on}），
     * 因此值恒为列表。这是「看板中间列按筛选器检索」的口径来源。</p>
     */
    private Map<String, List<String>> filterEventIndex = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(CONFIG_PATH).getInputStream()) {
            CameraEventConfigData data = objectMapper.readValue(in, CameraEventConfigData.class);
            if (data != null) {
                if (data.getCameras() != null) {
                    this.cameras = data.getCameras();
                }
                if (data.getEvents() != null) {
                    this.events = data.getEvents();
                }
            }
        } catch (Exception e) {
            log.error("Failed to load camera event config: {}, fallback to empty config", CONFIG_PATH, e);
            this.cameras = new LinkedHashMap<>();
            this.events = new LinkedHashMap<>();
        }
        // 构建 topic / camera_name 反查索引
        Map<String, CameraChannel> index = new LinkedHashMap<>();
        cameras.forEach((id, cam) -> {
            if (cam != null && cam.getTopic() != null) {
                index.put(cam.getTopic(), cam);
            }
            if (cam != null && cam.getCameraName() != null) {
                index.put("/sensor/" + cam.getCameraName() + "/video", cam);
            }
        });
        this.cameraIndex = index;

        // 构建 SECTION → 事件 反查索引（事件名与筛选器名都登记）
        Map<String, List<String>> eventIndex = new LinkedHashMap<>();
        events.forEach((name, entry) -> {
            if (entry == null) {
                return;
            }
            String eventKey = MxfSpecialEvents.normalizeSection(name);
            if (eventKey != null) {
                eventIndex.computeIfAbsent(eventKey, k -> new ArrayList<>()).add(name);
            }
            String filterKey = MxfSpecialEvents.normalizeSection(entry.getFilter());
            if (filterKey != null && !filterKey.equals(eventKey)) {
                eventIndex.computeIfAbsent(filterKey, k -> new ArrayList<>()).add(name);
            }
        });
        this.sectionEventIndex = eventIndex;

        // 构建 筛选器 → 事件 反查索引（filter 缺失/空白时回退用事件名本身）
        Map<String, List<String>> filterIndex = new LinkedHashMap<>();
        events.forEach((name, entry) -> {
            if (entry == null || name == null || name.isBlank()) {
                return;
            }
            String raw = entry.getFilter();
            String filter = raw == null || raw.isBlank() ? name.trim() : raw.trim();
            filterIndex.computeIfAbsent(filter, k -> new ArrayList<>()).add(name);
        });
        this.filterEventIndex = filterIndex;

        log.info("Camera event config loaded: {} cameras, {} events, {} filters",
                cameras.size(), events.size(), filterEventIndex.size());
    }

    /**
     * 筛选器名 → 事件名列表（看板中间列「按筛选器检索」的口径）。
     *
     * <p>先按原始 filter 名精确匹配（大小写敏感，与配置文件一致）；未命中时再做一次
     * 归一化宽松匹配（容忍 {@code HIGHBEAM-ON-FILTER} 这类 SECTION 写法）。</p>
     *
     * <p>两轮都没命中时，若入参本身是配置里的事件名，则当作「只检索这一个事件」返回：
     * 筛选器名只是平台侧分组（如 {@code hw_resource_monitor_filter} → {@code system_crash}），
     * 库里落的是 {@code event_name}，所以直接传事件名进来同样应当成立。</p>
     *
     * @param filter 筛选器名（{@code events.*.filter}）或事件名
     * @return 该筛选器对应的事件名（可能多个，保持配置顺序）；未命中返回空列表
     */
    public List<String> resolveEventsByFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return List.of();
        }
        String key = filter.trim();
        List<String> exact = filterEventIndex.get(key);
        if (exact != null) {
            return List.copyOf(exact);
        }
        String normalized = MxfSpecialEvents.normalizeSection(key);
        if (normalized == null) {
            return List.of();
        }
        for (Map.Entry<String, List<String>> entry : filterEventIndex.entrySet()) {
            if (normalized.equals(MxfSpecialEvents.normalizeSection(entry.getKey()))) {
                return List.copyOf(entry.getValue());
            }
        }
        if (events.containsKey(key)) {
            return List.of(key);
        }
        return List.of();
    }

    /**
     * 全部筛选器 → 事件名列表（不可变，顺序即配置文件顺序）。
     */
    public Map<String, List<String>> getFilterEventIndex() {
        return Collections.unmodifiableMap(filterEventIndex);
    }

    /**
     * SECTION 名 → 事件名列表（基于 events 配置的事件名与 filter 字段解析）。
     *
     * <p>匹配顺序：
     * <ol>
     *   <li>8 类特殊事件及其 SECTION 别名（如 {@code AEB-ACTIVATE} → {@code fushion_aeb}，
     *       见 {@link MxfSpecialEvents}）；</li>
     *   <li>SECTION 名（归一化后）与事件名或其筛选器名相同（如 {@code MEB-BRAKE-EVENT} →
     *       {@code meb_brake_event}、{@code HW-RESOURCE-MONITOR-FILTER} → {@code system_crash}）。</li>
     * </ol>
     * 一个筛选器对应多个事件变体时（如 {@code highbeam_on_filter}），无法仅凭 SECTION 名
     * 区分，返回全部变体，由调用方决定统计口径。
     *
     * @return 命中的事件名列表（可能为多个）；未命中返回空列表
     */
    public List<String> resolveEventsBySection(String section) {
        String special = MxfSpecialEvents.resolveEvent(section);
        if (special != null && events.containsKey(special)) {
            return List.of(special);
        }
        String key = MxfSpecialEvents.normalizeSection(section);
        if (key == null) {
            return List.of();
        }
        List<String> matched = sectionEventIndex.get(key);
        return matched == null ? List.of() : List.copyOf(matched);
    }

    /**
     * 查询事件（触发类型）对应的摄像头通道列表。
     *
     * @param eventName 触发类型（meta.event_name）
     * @return 该事件需要转换的摄像头通道； eventName 为空或未在配置中 → 全部摄像头（fail-open）；
     * 事件在配置中且摄像头列表为空（如 system_crash"无"）→ 空列表（不转换任何摄像头）
     */
    public List<CameraChannel> getEventCameras(String eventName) {
        if (eventName == null || eventName.isBlank() || !events.containsKey(eventName)) {
            return allCameras();
        }
        EventEntry entry = events.get(eventName);
        List<String> ids = entry == null || entry.getCameras() == null ? List.of() : entry.getCameras();
        List<CameraChannel> result = new ArrayList<>();
        for (String id : ids) {
            CameraChannel cam = cameras.get(id);
            if (cam != null) {
                result.add(cam);
            } else {
                log.warn("Unknown camera id '{}' in event config for event '{}'", id, eventName);
            }
        }
        return result;
    }

    /**
     * 事件对应的摄像头 topic 集合（bag topic，如 /sensor/camera_front_wide/video）
     */
    public List<String> getEventCameraTopics(String eventName) {
        return getEventCameras(eventName).stream()
                .map(CameraChannel::getTopic)
                .toList();
    }

    /**
     * 判断事件是否显式配置（未知事件走 fail-open 全量逻辑）
     */
    public boolean isKnownEvent(String eventName) {
        return eventName != null && !eventName.isBlank() && events.containsKey(eventName);
    }

    /**
     * 根据 bag topic 或 camera_name 反查摄像头通道（用于按路取目标分辨率）
     */
    public CameraChannel getCameraByTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        CameraChannel cam = cameraIndex.get(topic);
        if (cam != null) {
            return cam;
        }
        // 宽松匹配：容忍大小写差异
        return cameraIndex.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(topic.trim()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 全部摄像头通道
     */
    public List<CameraChannel> allCameras() {
        return List.copyOf(cameras.values());
    }

    public Map<String, CameraChannel> getCameras() {
        return Collections.unmodifiableMap(cameras);
    }

    public Map<String, EventEntry> getEvents() {
        return Collections.unmodifiableMap(events);
    }

    /**
     * 摄像头通道定义
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CameraChannel {
        /**
         * CFDI sensor ID（如 F100_front_wide），同时也是 h265 文件名前缀
         */
        private String id;
        /**
         * 中文名（前视广角等）
         */
        private String displayName;
        /**
         * bag topic 中的 camera 名称（camera_front_wide）
         */
        private String cameraName;
        /**
         * bag topic（/sensor/camera_front_wide/video）
         */
        private String topic;
        /**
         * 分辨率档位（1080P / 720P）
         */
        private String resolution;
        /**
         * 目标宽度
         */
        private int width;
        /**
         * 目标高度
         */
        private int height;
    }

    /**
     * 事件定义：触发类型 → 摄像头路
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventEntry {
        /**
         * 筛选器名称
         */
        private String filter;
        /**
         * 该事件需要转换的摄像头 CFDI sensor ID 列表；空列表 = 无摄像头（"无"）
         */
        private List<String> cameras;
    }

    /**
     * 配置文件根结构
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CameraEventConfigData {
        private Map<String, CameraChannel> cameras;
        private Map<String, EventEntry> events;
    }
}