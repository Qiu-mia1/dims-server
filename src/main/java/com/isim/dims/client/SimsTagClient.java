package com.isim.dims.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SIMS 片段域标签词表客户端。
 *
 * <p>端点（SIMS 契约统一端点，见 {@code platform-contracts/apis/sims.openapi.yaml}）——
 * 三域共用同一路径，靠必选 query 参数 {@code scope} 区分；本客户端固定传 {@code scope=FRAGMENT}：
 * <ul>
 *   <li>GET  {@code /tag-categories/tree?scope=FRAGMENT}</li>
 *   <li>GET  {@code /tags?scope=FRAGMENT}（分页）</li>
 *   <li>POST {@code /tags?scope=FRAGMENT}</li>
 *   <li>POST {@code /tags/{id}/update?scope=FRAGMENT}</li>
 *   <li>POST {@code /tags/{id}/remove?scope=FRAGMENT}</li>
 *   <li>POST {@code /tag-categories?scope=FRAGMENT}</li>
 *   <li>POST {@code /tag-categories/{id}/rename?scope=FRAGMENT}</li>
 *   <li>POST {@code /tag-categories/{id}/remove?scope=FRAGMENT}</li>
 * </ul>
 *
 * <p><b>信封解包</b>：SIMS 侧 {@code GlobalResponseAdvice} 覆盖 {@code com.isim3.sims} 全包，
 * 成功响应为 {@code {code,msg,data}}，业务体在 {@code data} 里；直接读顶层 {@code items}
 * 必然取不到。无 {@code data} 字段时按裸 DTO 处理，兼容尚未启用信封的实例。
 *
 * <p><b>身份</b>：本类不决定用哪个 token —— 由调用方传入。标签管理（{@code TagController}）
 * 全链路透传用户 token；{@code SimsTagCatalog} 后台词表缓存用服务 token
 * （持 {@code dims:tag:read}，IAMS 客户端授权）。
 *
 * @feature: tag
 */
@Slf4j
@Component
public class SimsTagClient {

    /** 片段域 scope（SIMS 契约 v3.2.0 起标签查询端点 scope 必选；本客户端只消费片段域）。 */
    private static final String FRAGMENT_SCOPE = "FRAGMENT";

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;

    public SimsTagClient(RestClient.Builder restClientBuilder,
                         @Value("${dims.sims.base-url:http://www.jmc.svc/sims/v1}") String baseUrl) {
        this.restClientBuilder = restClientBuilder;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    // ============================ 读 ============================

    /** 片段域分类树（嵌套结构，顶层唯一节点为域根）。 */
    public JsonNode categoryTree(String bearerToken) {
        return unwrap(send(HttpMethod.GET, "/tag-categories/tree", bearerToken, null, null));
    }

    /**
     * 标签分页列表。
     *
     * @param categoryId 叶子分类过滤（可空）
     * @param keyword    标签名模糊匹配（可空）
     * @param nodeType   BUILTIN / CUSTOM 分区过滤（可空）
     */
    public JsonNode listTags(String bearerToken, int page, int pageSize,
                             String categoryId, String keyword, String nodeType) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("page", String.valueOf(page));
        query.add("pageSize", String.valueOf(pageSize));
        addIfPresent(query, "categoryId", categoryId);
        addIfPresent(query, "keyword", keyword);
        addIfPresent(query, "nodeType", nodeType);
        return unwrap(send(HttpMethod.GET, "/tags", bearerToken, query, null));
    }

    // ============================ 标签写 ============================

    public JsonNode createTag(String bearerToken, String categoryId, String name, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        if (StringUtils.hasText(description)) {
            body.put("description", description);
        }
        return unwrap(send(HttpMethod.POST, "/tags", bearerToken, null, body));
    }

    /**
     * 修改标签。
     *
     * <p>不带 {@code categoryId}：SIMS {@code TagUpdateRequest} 无此字段，
     * 且契约语义上标签的分类不可变更（要换分类 = 删掉重建）。
     */
    public JsonNode updateTag(String bearerToken, String tagId, String name, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (StringUtils.hasText(description)) {
            body.put("description", description);
        }
        return unwrap(send(HttpMethod.POST, "/tags/" + tagId + "/update", bearerToken, null, body));
    }

    // ============================ 分类写 ============================

    public JsonNode createCategory(String bearerToken, String parentId, String name, boolean isLeaf) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parentId", parentId);
        body.put("name", name);
        body.put("isLeaf", isLeaf);
        return unwrap(send(HttpMethod.POST, "/tag-categories", bearerToken, null, body));
    }

    public void renameCategory(String bearerToken, String categoryId, String name) {
        send(HttpMethod.POST, "/tag-categories/" + categoryId + "/rename", bearerToken, null,
                Map.of("name", name));
    }

    // ============================ 删除（必须用用户 token） ============================

    public void removeTag(String bearerToken, String tagId) {
        send(HttpMethod.POST, "/tags/" + tagId + "/remove", bearerToken, null, null);
    }

    public void removeCategory(String bearerToken, String categoryId) {
        send(HttpMethod.POST, "/tag-categories/" + categoryId + "/remove", bearerToken, null, null);
    }

    // ============================ 内部实现 ============================

    private JsonNode send(HttpMethod method, String path, String bearerToken,
                          MultiValueMap<String, String> query, Object body) {
        // 凭证为空时快速失败 —— 不能发出 Authorization: "Bearer null"。
        // 那种请求会被 SIMS 回一个 401「未登录或登录已过期」，把「服务间凭证没拿到」
        // 误导成「用户登录态过期」，排查方向直接跑偏（2026-09-23 实测踩坑）。
        if (!StringUtils.hasText(bearerToken)) {
            throw new SimsRemoteException(502,
                    "SIMS 调用凭证未就绪（服务 token 未获取到，或用户 token 缺失）—— 已中止调用，"
                            + "避免发出必然被拒的空凭证。请检查 iams.client.* 与 dims.sims.fallback-token 配置");
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + path)
                .queryParams(withScope(query))
                .build()
                .toUri();
        try {
            RestClient.RequestBodySpec spec = restClientBuilder.build()
                    .method(method)
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.body(body);
            }
            String raw = spec.retrieve().body(String.class);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return objectMapper.readTree(raw);
        } catch (RestClientResponseException ex) {
            // 上游 4xx 原样保留（403 缺 sims:tag:delete、400 分类下有子节点等，文案用 SIMS 原文）；
            // 5xx 归一为 502 —— DIMS 是代理，上游故障即 Bad Gateway
            throw new SimsRemoteException(normalizeStatus(ex.getStatusCode().value()),
                    extractMessage(ex.getResponseBodyAsString()), ex);
        } catch (IOException ex) {
            throw new SimsRemoteException(502, "SIMS 响应不是合法 JSON: " + ex.getMessage(), ex);
        } catch (RestClientException ex) {
            throw new SimsRemoteException(502, unreachableMessage(ex), ex);
        }
    }

    /**
     * 连接层失败的可诊断文案。
     *
     * <p>Spring 默认给的是 {@code I/O error on GET request for "…": www.jmc.svc} ——
     * 末尾那截其实就是 {@link UnknownHostException} 的主机名，读起来像「上游服务报错」，
     * 实际是<b>本端 DNS 解析失败</b>，排查方向完全不同。
     *
     * <p>踩过的坑（2026-09-23 测试环境）：{@code dims.sims.base-url} 配成宿主机/网关对外别名
     * {@code www.jmc.svc}，宿主机能解析但容器内不存在，接口报 502，光看原文案定位不了。
     * 所以这里对解析失败单列文案，把出错主机名、当前生效的 base-url、覆盖途径一并写进
     * 响应体 —— 运维看接口返回即可定位，不必去翻日志。
     */
    private String unreachableMessage(RestClientException ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String detail = StringUtils.hasText(root.getMessage())
                ? root.getMessage()
                : root.getClass().getSimpleName();
        if (root instanceof UnknownHostException) {
            return "SIMS 地址解析失败（DNS）：" + detail
                    + "。当前 dims.sims.base-url=" + baseUrl
                    + "; 该地址必须在 dims-server 运行环境内可解析 —— 宿主机/网关对外别名"
                    + "（如 www.jmc.svc）在容器内可能不存在，请改用集群内服务名或可达 IP，"
                    + "并经环境变量 DIMS_SIMS_BASE_URL 或 Nacos 配置覆盖。";
        }
        return "SIMS 不可达: " + detail;
    }

    private static int normalizeStatus(int upstreamStatus) {
        return upstreamStatus >= 500 ? 502 : upstreamStatus;
    }

    /**
     * 剥离契约成功响应信封，取业务数据体。
     *
     * <p>无 {@code data} 字段时按裸 DTO 处理（兼容尚未启用信封的实例）。
     */
    private JsonNode unwrap(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode data = response.path("data");
        return data.isObject() || data.isArray() ? data : response;
    }

    /**
     * 从错误响应体提取可读文案。
     *
     * <p>优先取 SIMS 内部信封的 {@code msg}（{@code {code:数字,msg,data}}），
     * 其次 {@code message} / {@code error.message}（契约信封 {@code {error:{code,message}}}），
     * 都取不到则回落原文（截断 300 字符）——绝不吞成一句「请求失败」，
     * 否则运维无法判断到底是权限问题还是业务拒绝。
     */
    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "SIMS 未返回错误详情";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            for (JsonNode candidate : new JsonNode[]{
                    node.path("msg"),
                    node.path("message"),
                    node.path("error").path("message")}) {
                if (candidate.isTextual() && !candidate.asText().isBlank()) {
                    return candidate.asText();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体（网关 HTML 页等）：回落原文
        }
        String trimmed = body.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }

    /** 所有端点统一追加必选 {@code scope=FRAGMENT}（SIMS 契约 v3.2.0：缺失返回 400）。 */
    private static MultiValueMap<String, String> withScope(MultiValueMap<String, String> query) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("scope", FRAGMENT_SCOPE);
        if (query != null) {
            params.addAll(query);
        }
        return params;
    }

    private static void addIfPresent(MultiValueMap<String, String> query, String name, String value) {
        if (StringUtils.hasText(value)) {
            query.add(name, value.trim());
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
