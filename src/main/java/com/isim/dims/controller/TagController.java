package com.isim.dims.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.isim.dims.client.CurrentUserTokenResolver;
import com.isim.dims.client.SimsRemoteException;
import com.isim.dims.client.SimsTagClient;
import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.PageResult;
import com.isim.dims.dto.SliceTagResponse;
import com.isim.dims.dto.TagCategoryNodeView;
import com.isim.dims.dto.TagItemView;
import com.isim.dims.security.RequireSliceWrite;
import com.isim.dims.service.SimsTagCatalog;
import com.isim.dims.service.TagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 标签 Controller —— SIMS 片段域标签词表的 <b>BFF 代理层</b>。
 *
 * <p><b>数据来源（2026-09-23 重构）</b>：DIMS 不再持有标签定义。标签与分类树全部由 SIMS
 * 以 {@code scope=FRAGMENT} 托管（「定义集中、引用分散」），本 Controller 只做转发，
 * 本地 {@code tag} 表已随 V29 移除。
 *
 * <p><b>身份策略（2026-09-23 定案：调 SIMS 一律透传用户 token；2026-10-13 移除服务 token 兜底）</b>：
 * <ul>
 *   <li><b>调 SIMS 的端点</b>（树 / 列表 / 建 / 改 / 删）一律<b>严格透传当前用户的 token</b>
 *       （{@link CurrentUserTokenResolver#currentToken()}，无 JWT 直接抛异常 ——
 *       {@code /v1/tags/**} 在 SecurityConfig URL 层本就要求 authenticated，生产环境必有 JWT）。
 *       理由：SIMS 才是标签权限的<b>唯一授权源</b>（{@code sims:tag:read/write/delete}）；
 *       服务 token 不带 authorities，且 SIMS 侧的 service-token 放行分支在部分部署里未生效，
 *       用服务 token 会换回语义模糊的 403。透传用户 token 后，判权与审计都落在 SIMS 一处。
 *       历史上曾留过「无用户 JWT 时回退服务 token」的兜底分支：它在 URL 层 authenticated
 *       约束下是死代码，且一旦触发就是绕过用户身份的越权通道（SIMS 侧收紧后服务 token
 *       不再被写端点放行），2026-10-13 移除；</li>
 *   <li><b>例外</b>：{@code /tags/options} 走本地词表缓存（{@code SimsTagCatalog}），
 *       与「切片列表取名」「后台导入流水线」共用同一份缓存，仍用服务 token
 *       （后台线程没有用户上下文；服务 token 仅此一处使用）。</li>
 * </ul>
 *
 * <p><b>权限矩阵</b>：调 SIMS 的端点<b>只在 SIMS 判权</b>，DIMS 侧仅要求已登录 ——
 * 不再叠加 {@code dims:tag:*}。两套权限点会让「只在 DIMS 配了、SIMS 没授权」这类
 * 半配置状态产生难以诊断的 403。
 * 唯一例外：切片打标（{@code PUT /data-slices/{sliceId}/tags}）写的是 DIMS 自己的
 * {@code data_slice_tag} 关联表、不经过 SIMS，仍由 {@code dims:tag:write} 把关。
 *
 * <p><b>内置区只读</b>：{@code nodeType=BUILTIN} 的子树一律拒绝写入（400
 * {@code TAG_BUILTIN_READONLY}），且**在 DIMS 侧判定，不把请求转给 SIMS 再靠它 403 拦** ——
 * 否则用户看到的是语义模糊的「没权限」，会去找管理员开权限，而实际问题是他不该改内置区。
 *
 * <p>对应前端页面：{@code dims-web/src/views/TagManage.vue}。
 *
 * @feature: tag
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class TagController {

    private static final String BUILTIN = "BUILTIN";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final SimsTagClient simsTagClient;
    private final SimsTagCatalog simsTagCatalog;
    private final CurrentUserTokenResolver currentUserTokenResolver;
    private final TagService tagService;

    // ========================================================================
    // 读：分类树 / 标签列表 / 下拉选项
    // ========================================================================

    /** 片段域分类树（含内置/自定义两个分区，前端按 nodeType 分 tab 展示）。 */
    @GetMapping("/tags/tree")
    public ResponseEntity<?> tagTree(
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            return ResponseEntity.ok(loadTree());
        } catch (Exception ex) {
            return failure(ex, "TAG_TREE_FAILED", traceId);
        }
    }

    /** 标签分页列表。 */
    @GetMapping("/tags")
    public ResponseEntity<?> listTags(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "categoryId", required = false) String categoryId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "nodeType", required = false) String nodeType,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            JsonNode payload = simsTagClient.listTags(currentUserTokenResolver.currentToken(),
                    Math.max(page, 1), clampPageSize(pageSize), categoryId, keyword, nodeType);
            JsonNode items = payload == null ? null : payload.path("items");
            List<TagItemView> views = new ArrayList<>();
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    views.add(toItemView(item));
                }
            }
            long total = payload == null ? views.size() : payload.path("total").asLong(views.size());
            return ResponseEntity.ok(new PageResult<>(views, total));
        } catch (Exception ex) {
            return failure(ex, "TAG_LIST_FAILED", traceId);
        }
    }

    /**
     * 全量标签下拉项。
     *
     * <p>走 {@link SimsTagCatalog} 进程内缓存而非直连 SIMS：切片打标下拉、切片列表取名
     * 都依赖它，直连会把这些高频读取路径变成外部调用热点。
     */
    @GetMapping("/tags/options")
    public ResponseEntity<?> tagOptions(
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            return ResponseEntity.ok(simsTagCatalog.all());
        } catch (Exception ex) {
            return failure(ex, "TAG_OPTIONS_FAILED", traceId);
        }
    }

    // ========================================================================
    // 标签写
    // ========================================================================

    @PostMapping("/tags")
    public ResponseEntity<?> createTag(
            @Valid @RequestBody CreateTagRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            requireCustomLeaf(request.getCategoryId());
            JsonNode created = simsTagClient.createTag(currentUserTokenResolver.currentToken(),
                    request.getCategoryId(), request.getName(), request.getDescription());
            simsTagCatalog.invalidate();
            return ResponseEntity.status(201).body(toItemView(created));
        } catch (Exception ex) {
            return failure(ex, "TAG_CREATE_FAILED", traceId);
        }
    }

    /**
     * 修改标签（仅名称/描述）。
     *
     * <p>不带 {@code categoryId}：SIMS 的 {@code TagUpdateRequest} 无此字段，
     * 契约语义上标签的分类不可变更。
     */
    @PutMapping("/tags/{tagId}")
    public ResponseEntity<?> updateTag(
            @PathVariable("tagId") String tagId,
            @Valid @RequestBody UpdateTagRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            JsonNode updated = simsTagClient.updateTag(currentUserTokenResolver.currentToken(),
                    tagId, request.getName(), request.getDescription());
            simsTagCatalog.invalidate();
            return ResponseEntity.ok(toItemView(updated));
        } catch (Exception ex) {
            return failure(ex, "TAG_UPDATE_FAILED", traceId);
        }
    }

    // ========================================================================
    // 分类写
    // ========================================================================

    @PostMapping("/tags/categories")
    public ResponseEntity<?> createCategory(
            @Valid @RequestBody CreateCategoryRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            // 先判 parentId 再拉树：既省一次外部调用，也避免顶层建节点产生游离的域根
            if (!StringUtils.hasText(request.getParentId())) {
                throw new TagRejectedException("TAG_PARENT_REQUIRED",
                        "必须指定父分类（DIMS 只允许在「自定义标签」子树下新建）");
            }
            requireCustomGroup(request.getParentId());
            JsonNode created = simsTagClient.createCategory(currentUserTokenResolver.currentToken(),
                    request.getParentId(), request.getName(), Boolean.TRUE.equals(request.getIsLeaf()));
            simsTagCatalog.invalidate();
            return ResponseEntity.status(201).body(toCategoryView(created));
        } catch (Exception ex) {
            return failure(ex, "TAG_CATEGORY_CREATE_FAILED", traceId);
        }
    }

    @PutMapping("/tags/categories/{categoryId}")
    public ResponseEntity<?> renameCategory(
            @PathVariable("categoryId") String categoryId,
            @Valid @RequestBody RenameCategoryRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            requireCustomNode(categoryId);
            simsTagClient.renameCategory(currentUserTokenResolver.currentToken(), categoryId, request.getName());
            simsTagCatalog.invalidate();
            return ResponseEntity.noContent().build();
        } catch (Exception ex) {
            return failure(ex, "TAG_CATEGORY_RENAME_FAILED", traceId);
        }
    }

    // ========================================================================
    // 删除（透传用户 token —— 服务 token 在 SIMS 必 403）
    // ========================================================================

    @DeleteMapping("/tags/{tagId}")
    public ResponseEntity<?> deleteTag(
            @PathVariable("tagId") String tagId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            simsTagClient.removeTag(currentUserTokenResolver.currentToken(), tagId);
            simsTagCatalog.invalidate();
            return ResponseEntity.noContent().build();
        } catch (Exception ex) {
            return failure(ex, "TAG_DELETE_FAILED", traceId);
        }
    }

    @DeleteMapping("/tags/categories/{categoryId}")
    public ResponseEntity<?> deleteCategory(
            @PathVariable("categoryId") String categoryId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        try {
            requireCustomNode(categoryId);
            simsTagClient.removeCategory(currentUserTokenResolver.currentToken(), categoryId);
            simsTagCatalog.invalidate();
            return ResponseEntity.noContent().build();
        } catch (Exception ex) {
            return failure(ex, "TAG_CATEGORY_DELETE_FAILED", traceId);
        }
    }

    // ========================================================================
    // 切片打标（本地关联，不随标签定义外移）
    // ========================================================================

    /** 查询切片当前关联的标签（标签名由词表缓存解析）。 */
    @GetMapping("/data-slices/{sliceId}/tags")
    public ResponseEntity<?> getSliceTags(
            @PathVariable("sliceId") String sliceId,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("getSliceTags request: sliceId={}, traceId={}", sliceId, traceId);
        try {
            List<SliceTagResponse> tags = tagService.getSliceTags(sliceId);
            return ResponseEntity.ok(Map.of("tags", tags));
        } catch (Exception e) {
            log.error("getSliceTags failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SLICE_TAGS_FAILED", e.getMessage(), traceId));
        }
    }

    /**
     * 批量设置切片标签（覆盖式更新）。
     *
     * <p>写的是 DIMS 本地 {@code data_slice_tag} 关联表（不经过 SIMS），因此不再用
     * 已退役的 {@code dims:tag:write}，改挂切片自身的写权限 {@code dims:slice:write}
     * —— 打标本质是「修改切片的关联数据」，与切片上传/导入同源。
     */
    @RequireSliceWrite
    @PutMapping("/data-slices/{sliceId}/tags")
    public ResponseEntity<?> setSliceTags(
            @PathVariable("sliceId") String sliceId,
            @Valid @RequestBody SetTagsRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "auto-generated") String traceId) {
        log.info("setSliceTags request: sliceId={}, tagIds={}, source={}, traceId={}",
                sliceId, request.getTagIds(), request.getSource(), traceId);
        try {
            tagService.setSliceTags(sliceId, request.getTagIds(), request.getSource());
            return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
        } catch (Exception e) {
            log.error("setSliceTags failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ErrorResponse.of("SLICE_TAGS_SET_FAILED", e.getMessage(), traceId));
        }
    }

    // ========================================================================
    // 树校验：内置区只读 + 叶子约束
    // ========================================================================

    private List<TagCategoryNodeView> loadTree() {
        JsonNode tree = simsTagClient.categoryTree(currentUserTokenResolver.currentToken());
        if (tree == null || !tree.isArray()) {
            return List.of();
        }
        List<TagCategoryNodeView> nodes = new ArrayList<>();
        for (JsonNode node : tree) {
            nodes.add(toCategoryView(node));
        }
        return nodes;
    }

    /** 建标签的目标分类：必须存在、属自定义区、且是叶子。 */
    private void requireCustomLeaf(String categoryId) {
        TagCategoryNodeView node = requireNode(categoryId, "分类");
        if (!node.isLeaf()) {
            throw new TagRejectedException("TAG_CATEGORY_NOT_LEAF",
                    "仅叶子分类可挂标签：" + node.getName());
        }
    }

    /** 建分类的父节点：必须存在、属自定义区、且不是叶子。 */
    private void requireCustomGroup(String parentId) {
        TagCategoryNodeView node = requireNode(parentId, "父分类");
        if (node.isLeaf()) {
            throw new TagRejectedException("TAG_CATEGORY_NOT_LEAF",
                    "叶子分类不能再建子分类：" + node.getName());
        }
    }

    /** 改名/删除的目标节点：必须存在且属自定义区。 */
    private void requireCustomNode(String categoryId) {
        requireNode(categoryId, "分类");
    }

    private TagCategoryNodeView requireNode(String categoryId, String label) {
        TagCategoryNodeView node = findNode(loadTree(), categoryId);
        if (node == null) {
            throw new TagRejectedException("TAG_CATEGORY_NOT_FOUND", label + "不存在：" + categoryId);
        }
        if (BUILTIN.equalsIgnoreCase(node.getNodeType())) {
            throw new TagRejectedException("TAG_BUILTIN_READONLY",
                    "内置标签区只读，不可修改：" + node.getName());
        }
        return node;
    }

    /** 嵌套树按 id 递归查找（顶层只是域根，真正的分区/分类都在 children 里）。 */
    private static TagCategoryNodeView findNode(List<TagCategoryNodeView> nodes, String id) {
        if (nodes == null || id == null) {
            return null;
        }
        for (TagCategoryNodeView node : nodes) {
            if (id.equals(node.getId())) {
                return node;
            }
            TagCategoryNodeView hit = findNode(node.getChildren(), id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    // ========================================================================
    // 响应/错误映射
    // ========================================================================

    /**
     * SIMS {@code TagItem}（camelCase）→ 前端视图（出参由全局 SNAKE_CASE 策略渲染）。
     *
     * <p>手工逐字段映射的**原因**：SIMS 契约是 camelCase（{@code categoryId} /
     * {@code categoryPath} / {@code createdByName}），而 DIMS 侧全局 Jackson 策略是
     * SNAKE_CASE（{@code spring.jackson.property-naming-strategy}）。直接
     * {@code convertValue} 会按 {@code category_id} 去找字段 → 全部静默读成 null。
     */
    private TagItemView toItemView(JsonNode node) {
        if (node == null) {
            return null;
        }
        TagItemView view = new TagItemView();
        view.setId(textOf(node, "id"));
        view.setCategoryId(textOf(node, "categoryId"));
        view.setCategoryPath(textOf(node, "categoryPath"));
        view.setName(textOf(node, "name"));
        view.setDescription(textOf(node, "description"));
        view.setCreatedByName(textOf(node, "createdByName"));
        view.setCreatedAt(textOf(node, "createdAt"));
        return view;
    }

    /**
     * SIMS {@code TagCategoryTreeNode}（camelCase，递归）→ 前端视图。
     *
     * <p>⚠️ {@code isLeaf} / {@code isSystem} 是 SIMS 侧的键名，别按 DIMS 的
     * {@code is_leaf} 去读 —— 读错会让「内置区只读」校验静默失效（nodeType / isLeaf 全为
     * 默认值，任何节点都判定为可写）。
     */
    private TagCategoryNodeView toCategoryView(JsonNode node) {
        if (node == null) {
            return null;
        }
        TagCategoryNodeView view = new TagCategoryNodeView();
        view.setId(textOf(node, "id"));
        view.setParentId(textOf(node, "parentId"));
        view.setName(textOf(node, "name"));
        view.setNodeType(textOf(node, "nodeType"));
        view.setLeaf(boolOf(node, "isLeaf"));
        view.setSystem(boolOf(node, "isSystem"));
        view.setSortOrder(node.path("sortOrder").asInt(0));
        JsonNode children = node.path("children");
        if (children.isArray()) {
            List<TagCategoryNodeView> list = new ArrayList<>();
            for (JsonNode child : children) {
                list.add(toCategoryView(child));
            }
            view.setChildren(list);
        }
        return view;
    }

    /** 取文本字段；缺失 / JSON null → null（避免 NullNode.asText() 落成字符串 "null"）。 */
    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static boolean boolOf(JsonNode node, String field) {
        return node.path(field).asBoolean(false);
    }

    /**
     * 统一错误出口。
     *
     * @param fallbackCode 非预期异常的兜底错误码（本地策略拒绝与 SIMS 错误各有自己的码）
     */
    private ResponseEntity<?> failure(Exception ex, String fallbackCode, String traceId) {
        if (ex instanceof TagRejectedException rejected) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of(rejected.getCode(), rejected.getMessage(), traceId));
        }
        if (ex instanceof SimsRemoteException remote) {
            return fromSims(remote, traceId);
        }
        log.error("[TagProxy] {} 失败: {}", fallbackCode, ex.getMessage(), ex);
        return ResponseEntity.status(500).body(ErrorResponse.of(fallbackCode, ex.getMessage(), traceId));
    }

    private ResponseEntity<?> fromSims(SimsRemoteException ex, String traceId) {
        int status = ex.getStatus();
        if (status == 403) {
            // 用 SIMS_FORBIDDEN 而非 FORBIDDEN：前端对 FORBIDDEN 一律显示「您没有操作权限」，
            // 会把「缺 sims:tag:*」误报成「你在 DIMS 没权限」，排查方向全错。
            // 文案指向 SIMS 侧授权（唯一授权源），让用户/运维去找对地方。
            log.warn("[TagProxy] SIMS 拒绝(403): {}", ex.getMessage());
            return ResponseEntity.status(403).body(ErrorResponse.of("SIMS_FORBIDDEN",
                    "SIMS 拒绝了本次操作，请确认账号已在 SIMS 侧授予对应标签权限。SIMS 原文：" + ex.getMessage(), traceId));
        }
        if (status == 401) {
            // 全链路透传用户 token 后，SIMS 的 401 = 该用户的 token 在 SIMS 侧无效/过期
            // —— 此时前端 clearToken() 跳登录页是**正确**行为（用户确实需要重新认证）。
            // （旧模式「服务 token 失效不能踢人」的顾虑已随透传策略消失。）
            log.warn("[TagProxy] SIMS 认证失败(401): {}", ex.getMessage());
            return ResponseEntity.status(401).body(ErrorResponse.of("SIMS_UNAUTHORIZED",
                    ex.getMessage(), traceId));
        }
        if (status < 400 || status >= 500) {
            log.error("[TagProxy] SIMS 不可用: status={}, message={}", status, ex.getMessage());
            return ResponseEntity.status(502).body(ErrorResponse.of("SIMS_UNAVAILABLE",
                    "SIMS 标签服务不可用，请稍后重试：" + ex.getMessage(), traceId));
        }
        // 4xx（400 分类下有子节点 / 404 标签不存在 / 409 冲突）：原样透传，文案用 SIMS 原文
        return ResponseEntity.status(status).body(ErrorResponse.of("SIMS_REJECTED", ex.getMessage(), traceId));
    }

    private static int clampPageSize(int pageSize) {
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    // ========================================================================
    // 请求体
    // ========================================================================

    /** 本地策略拒绝（非 SIMS 错误），带稳定的错误码。 */
    private static class TagRejectedException extends RuntimeException {

        private final String code;

        TagRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        String getCode() {
            return code;
        }
    }

    @Data
    public static class CreateTagRequest {

        @NotBlank(message = "分类不能为空")
        private String categoryId;

        @NotBlank(message = "标签名不能为空")
        @Size(max = 64, message = "标签名不能超过 64 个字符")
        private String name;

        @Size(max = 500, message = "描述不能超过 500 个字符")
        private String description;
    }

    @Data
    public static class UpdateTagRequest {

        @NotBlank(message = "标签名不能为空")
        @Size(max = 64, message = "标签名不能超过 64 个字符")
        private String name;

        @Size(max = 500, message = "描述不能超过 500 个字符")
        private String description;
    }

    @Data
    public static class CreateCategoryRequest {

        /** 父分类；为空直接拒绝（不允许建顶层节点，避免产生游离域根）。 */
        private String parentId;

        @NotBlank(message = "分类名不能为空")
        @Size(max = 64, message = "分类名不能超过 64 个字符")
        private String name;

        @NotNull(message = "是否叶子节点不能为空")
        private Boolean isLeaf;
    }

    @Data
    public static class RenameCategoryRequest {

        @NotBlank(message = "分类名不能为空")
        @Size(max = 64, message = "分类名不能超过 64 个字符")
        private String name;
    }

    /** 批量设置切片标签的请求体（对应 OpenAPI schema: SetTagsRequest）。 */
    @Data
    public static class SetTagsRequest {

        /** 标签 ID 列表（覆盖式更新）。 */
        private List<String> tagIds;

        /** 标签来源：auto | manual。 */
        private String source;
    }
}
