package com.isim.dims.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.isim.dims.client.SimsAccessTokenProvider;
import com.isim.dims.client.SimsTagClient;
import com.isim.dims.dto.TagRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SIMS 片段域词表缓存（替代被移除的本地 {@code tag} 表）。
 *
 * <p><b>为什么需要它</b>：{@code data_slice_tag} 只存 {@code tag_id}，标签名必须实时解析；
 * 而 SIMS 的 {@code /tags?scope=FRAGMENT} **不支持按 id 批量查询**（只支持 categoryId / keyword /
 * nodeType 过滤）。若每次列表都逐条回源，一页 20 条切片就是 N+1 次外部调用。
 * 片段域词表规模有限（百量级），整表缓存是唯一可行且成本最低的解法。
 *
 * <p><b>降级策略（关键）</b>：
 * <ul>
 *   <li>解析不到（历史脏引用 / 标签已在 SIMS 删除）→ 返回占位
 *       {@code 未知标签(<id 前 8 位>)}，<b>不抛异常</b> —— 一个失效引用不该让整个切片列表报错；</li>
 *   <li>拉取失败 → 沿用上一次成功缓存，仅告警；冷启动失败则返回空表（同样只降级不抛）。</li>
 * </ul>
 *
 * <p><b>防打爆</b>：拉取失败时同样推进 {@code loadedAt}，使下一次重试至少间隔一个 TTL，
 * 避免 SIMS 抖动期间每个请求都触发一次重试。
 *
 * @feature: tag
 */
@Slf4j
@Service
public class SimsTagCatalog {

    private static final int PAGE_SIZE = 200;

    /** 防御性上限：防止 total 异常导致无限翻页。50 × 200 = 1 万条，远超片段域词表规模。 */
    private static final int MAX_PAGES = 50;

    private static final int ID_PREFIX_LEN = 8;

    private final SimsTagClient simsTagClient;
    private final SimsAccessTokenProvider tokenProvider;
    private final long ttlMs;

    private volatile Map<String, TagRef> cache = Map.of();

    /** 上次「尝试加载」的时间戳；0 表示从未加载。 */
    private volatile long loadedAt;

    public SimsTagCatalog(SimsTagClient simsTagClient,
                          SimsAccessTokenProvider tokenProvider,
                          @Value("${dims.sims.catalog-ttl-ms:300000}") long ttlMs) {
        this.simsTagClient = simsTagClient;
        this.tokenProvider = tokenProvider;
        this.ttlMs = ttlMs;
    }

    /** 按 id 查标签；不存在返回 null。 */
    public TagRef find(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return null;
        }
        return current().get(tagId);
    }

    /**
     * 按标签名精确查找（中文标签名，如 "白天" / "夜间" / "锥桶阵列"）。
     * SIMS 词表规模有限（百量级），遍历一次可接受。
     * 同名标签取第一个（片段域分类路径唯一，同名概率极低）。
     *
     * @param name 标签中文名称；null/blank 返回 null
     * @return 匹配的 TagRef；不存在返回 null
     */
    public TagRef findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, TagRef> snapshot = current();
        for (TagRef ref : snapshot.values()) {
            if (name.equals(ref.getName())) {
                return ref;
            }
        }
        return null;
    }

    /** 按 id 取标签名；解析不到返回占位文案（不抛异常）。 */
    public String nameOf(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return "-";
        }
        TagRef ref = find(tagId);
        if (ref != null && ref.getName() != null && !ref.getName().isBlank()) {
            return ref.getName();
        }
        return "未知标签(" + prefixOf(tagId) + ")";
    }

    /** 全量快照（供标签下拉 / options 端点使用）。 */
    public List<TagRef> all() {
        return List.copyOf(current().values());
    }

    /** 写操作成功后调用：让下一次读取回源（标签/分类刚被改动）。 */
    public void invalidate() {
        loadedAt = 0;
    }

    // ========================================================================
    // 内部实现
    // ========================================================================

    private Map<String, TagRef> current() {
        Map<String, TagRef> snapshot = cache;
        if (loadedAt != 0 && System.currentTimeMillis() - loadedAt < ttlMs) {
            return snapshot;
        }
        return load();
    }

    /**
     * 单飞加载：并发请求同一时刻只有一个线程真正回源，其余直接拿结果。
     * 双检是因为进入 synchronized 前可能已有别的线程完成加载。
     */
    private synchronized Map<String, TagRef> load() {
        if (loadedAt != 0 && System.currentTimeMillis() - loadedAt < ttlMs) {
            return cache;
        }
        try {
            Map<String, TagRef> fresh = fetchAll();
            cache = fresh;
            log.debug("SIMS 片段域词表已加载：{} 条", fresh.size());
            return fresh;
        } catch (Exception ex) {
            log.warn("SIMS 词表拉取失败，沿用上一次缓存（{} 条）：{}", cache.size(), ex.getMessage());
            return cache;
        } finally {
            // 成功与失败都推进 loadedAt：失败也要退避，否则 SIMS 抖动期间变成每请求一次重试
            loadedAt = System.currentTimeMillis();
        }
    }

    private Map<String, TagRef> fetchAll() {
        String token = tokenProvider.token();
        Map<String, TagRef> result = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode payload = simsTagClient.listTags(token, page, PAGE_SIZE, null, null, null);
            JsonNode items = payload == null ? null : payload.path("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                // 远端该页无数据：结束翻页。不能只依赖 total —— 空页还继续翻会空转
                // （历史上基于 total 的翻页条件曾在此处空转）。
                break;
            }
            for (JsonNode item : items) {
                String id = item.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                result.put(id, new TagRef(id, textOrNull(item, "name"), textOrNull(item, "categoryPath")));
            }
            int total = payload.path("total").asInt(result.size());
            if (result.size() >= total) {
                break;
            }
        }
        return result;
    }

    private static String prefixOf(String tagId) {
        return tagId.length() > ID_PREFIX_LEN ? tagId.substring(0, ID_PREFIX_LEN) : tagId;
    }

    /** 缺失 / JSON null 一律返回 null（避免 NullNode.asText() 落成字符串 "null"）。 */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
