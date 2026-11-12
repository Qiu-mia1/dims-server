package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.UserDashboardConfigDetailResponse;
import com.isim.dims.dto.UserDashboardConfigExportFile;
import com.isim.dims.dto.UserDashboardConfigListResponse;
import com.isim.dims.dto.UserDashboardConfigSummaryResponse;
import com.isim.dims.entity.UserDashboardConfig;
import com.isim.dims.mapper.UserDashboardConfigMapper;
import com.isim.dims.service.UserDashboardConfigService;
import com.isim.dims.util.ReplayTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * 用户个性化配置方案 Service 实现（数据回放看板）
 *
 * <p>存储模型：一个用户可有多套方案，方案名在单用户内唯一
 * （{@code uk_user_dashboard_config_user_name}）。</p>
 *
 * <p><b>只存不读</b>：{@code config_json} 由前端组装，后端仅做 JsonNode → 字符串的序列化后
 * 原样落库，不解析字段语义、不做内容校验（空对象 {@code {}} 亦合法）。</p>
 *
 * <p><b>导入 / 导出</b>：导出走 {@link UserDashboardConfigExportFile} 自描述包装
 * （保留 description，给格式演进留 version）；导入以<b>文件名</b>为方案名，
 * 并以「create + 捕获唯一约束冲突」复用同一条重名检查路径。</p>
 *
 * @feature: replay
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDashboardConfigServiceImpl implements UserDashboardConfigService {

    /** 方案名称最大长度，与 DDL {@code name VARCHAR(120)} 对齐 */
    private static final int NAME_MAX_LENGTH = 120;

    /** 方案描述最大长度，与 DDL {@code description VARCHAR(500)} 对齐 */
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    /** 导出文件扩展名；导入时按键名不区分大小写地剥离 */
    private static final String JSON_EXTENSION = ".json";

    /** 导出文件内 {@code config_json} 字段名（全局 Jackson SNAKE_CASE 下的落盘键名） */
    private static final String FIELD_CONFIG_JSON = "config_json";

    /** 导出文件内 {@code name} 字段名 */
    private static final String FIELD_NAME = "name";

    /** 导出文件内 {@code description} 字段名 */
    private static final String FIELD_DESCRIPTION = "description";

    private final UserDashboardConfigMapper userDashboardConfigMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============ 列表 ============

    @Override
    public UserDashboardConfigListResponse list(String userId, int page, int pageSize) {
        String uid = requireUserId(userId);

        LambdaQueryWrapper<UserDashboardConfig> wrapper = new LambdaQueryWrapper<UserDashboardConfig>()
                .eq(UserDashboardConfig::getUserId, uid);

        // 手动分页：MyBatis-Plus 3.5.9 移除了 PaginationInnerInterceptor，
        // selectPage 的自动计数与 LIMIT 注入不可用（与 ExportServiceImpl / DashboardServiceImpl 一致）。
        // count 的 wrapper 不能带 ORDER BY —— PG 拒绝聚合查询对非聚合列排序，排序只加到列表查询。
        long total = userDashboardConfigMapper.selectCount(wrapper);
        int offset = (page - 1) * pageSize;
        List<UserDashboardConfig> rows = userDashboardConfigMapper.selectList(
                wrapper.clone().orderByDesc(UserDashboardConfig::getCreatedAt)
                        .last("LIMIT " + pageSize + " OFFSET " + offset));

        UserDashboardConfigListResponse resp = new UserDashboardConfigListResponse();
        resp.setItems(rows.stream().map(this::toSummaryResponse).toList());
        resp.setTotal(total);
        resp.setPage(page);
        resp.setPageSize(pageSize);
        return resp;
    }

    // ============ 新建 ============

    @Override
    public UserDashboardConfigDetailResponse create(String userId, String name, String description,
                                                    JsonNode configJson) {
        String uid = requireUserId(userId);
        String safeName = requireName(name);
        String safeDescription = normalizeDescription(description);
        String json = requireConfigJson(configJson);

        UserDashboardConfig row = new UserDashboardConfig();
        row.setUserId(uid);
        row.setName(safeName);
        row.setDescription(safeDescription);
        row.setConfigJson(json);
        try {
            userDashboardConfigMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 撞 uk_user_dashboard_config_user_name：同一用户内方案名重复。
            // 此处刻意采用「直接插 + 捕获唯一约束冲突」而非「先查后插」：
            //   ① 原子、无 TOCTOU 竞态（先查后插在并发下两个请求都能通过预检，后到者仍会撞库）；
            //   ② 与 SceneRuleController 的 create/update 处理惯例一致。
            // 也刻意不再保留旧 saveConfig 的「撞约束→回落改更新」兜底——多方案模型下
            // create 永远插入新 UUID 行，撞约束的语义就是「名称重复」，回落更新是错的。
            log.warn("create dashboard config name conflict: userId={}, name={}", uid, safeName);
            throw nameConflict(safeName, e);
        }
        return toDetailResponse(row);
    }

    // ============ 详情 ============

    @Override
    public UserDashboardConfigDetailResponse getDetail(String userId, String configId) {
        String uid = requireUserId(userId);
        String cid = requireConfigId(configId);
        return toDetailResponse(requireOwned(uid, cid));
    }

    // ============ 编辑（全量替换） ============

    @Override
    public UserDashboardConfigDetailResponse update(String userId, String configId,
                                                    String name, String description, JsonNode configJson) {
        String uid = requireUserId(userId);
        String cid = requireConfigId(configId);
        String safeName = requireName(name);
        String safeDescription = normalizeDescription(description);
        String json = requireConfigJson(configJson);

        UserDashboardConfig row = requireOwned(uid, cid);
        row.setName(safeName);
        // 全量替换语义：description 为 null 即表示清空。
        // 依赖实体上 @TableField(updateStrategy = FieldStrategy.ALWAYS)，
        // 否则 MyBatis-Plus 默认忽略 null 字段，这里的「清空」会静默失效。
        row.setDescription(safeDescription);
        row.setConfigJson(json);
        // strictUpdateFill 仅在字段为 null 时填充；此处 entity 来自 DB、updatedAt 已非 null，
        // 自动填充不会生效，必须显式刷新。
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            userDashboardConfigMapper.updateById(row);
        } catch (DuplicateKeyException e) {
            log.warn("update dashboard config name conflict: userId={}, configId={}, name={}", uid, cid, safeName);
            throw nameConflict(safeName, e);
        }
        return toDetailResponse(row);
    }

    // ============ 删除（物理删除） ============

    @Override
    public void delete(String userId, String configId) {
        String uid = requireUserId(userId);
        String cid = requireConfigId(configId);
        // 先做归属校验，不存在/不属于该用户 → 404，而非静默成功。
        // （注：项目既有 delete 端点把异常统一映射为 500，此处按 toError 前缀分流到 404，
        //   是刻意选择更规范的语义。）
        requireOwned(uid, cid);
        userDashboardConfigMapper.deleteById(cid);
    }

    // ============ 导出 ============

    @Override
    public UserDashboardConfigExportFile export(String userId, String configId) {
        String uid = requireUserId(userId);
        String cid = requireConfigId(configId);
        UserDashboardConfig row = requireOwned(uid, cid);

        UserDashboardConfigExportFile file = new UserDashboardConfigExportFile();
        file.setVersion(UserDashboardConfigExportFile.CURRENT_VERSION);
        file.setExportedAt(ReplayTimeUtil.formatLocalDateTime(LocalDateTime.now(ZoneOffset.UTC)));
        file.setName(row.getName());
        file.setDescription(row.getDescription());
        // 复用 parse()：存量脏数据（非法 JSON）退化为 null，而不是让导出整体失败
        file.setConfigJson(parse(row.getConfigJson()));
        return file;
    }

    // ============ 导入 ============

    @Override
    public UserDashboardConfigDetailResponse importConfig(String userId, String fileName, byte[] content) {
        String uid = requireUserId(userId);
        if (content == null || content.length == 0) {
            throw new RuntimeException("USER_CONFIG_IMPORT_INVALID: file is empty");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new RuntimeException("USER_CONFIG_IMPORT_INVALID: file is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new RuntimeException("USER_CONFIG_IMPORT_INVALID: file must be a JSON object");
        }

        // 刻意只认包装结构的 config_json，不把「裸配置 JSON」当合法输入：
        // 静默猜测会让格式语义含糊——将来把裸 JSON 里的某个同名字段误判为包装头，
        // 排查成本远高于让用户导入时直接看到明确报错。
        JsonNode configJson = root.get(FIELD_CONFIG_JSON);
        if (configJson == null || configJson.isNull()) {
            throw new RuntimeException("USER_CONFIG_IMPORT_INVALID: file does not contain " + FIELD_CONFIG_JSON);
        }

        String name = deriveNameFromFileName(fileName, textOrNull(root.get(FIELD_NAME)));
        String description = textOrNull(root.get(FIELD_DESCRIPTION));
        log.info("import dashboard config: userId={}, fileName={}, derivedName={}", uid, fileName, name);

        // 复用 create：名称校验、重名检查（唯一约束）、落库口径完全一致
        return create(uid, name, description, configJson);
    }

    // ============ 私有辅助 ============

    /**
     * 按 {@code (id, user_id)} 双条件定位方案（归属校验下沉到 SQL）。
     *
     * <p>一次 DB 往返即可同时完成「存在性 + 归属」校验；他人名下的 configId 直接返回 null，
     * 不泄露「该行存在但属别人」。查询可命中 {@code uk_user_dashboard_config_user_name}
     * 复合索引的前导列 user_id。</p>
     */
    private UserDashboardConfig requireOwned(String userId, String configId) {
        UserDashboardConfig row = userDashboardConfigMapper.selectOne(
                new LambdaQueryWrapper<UserDashboardConfig>()
                        .eq(UserDashboardConfig::getId, configId)
                        .eq(UserDashboardConfig::getUserId, userId));
        if (row == null) {
            throw new RuntimeException("USER_CONFIG_NOT_FOUND: " + configId);
        }
        return row;
    }

    /**
     * 导入时的方案名派生：<b>以文件名为准</b>。
     *
     * <p>处理顺序：剥目录前缀（部分客户端会带完整路径）
     * → trim → 剥 {@code .json}（不区分大小写）→ 再 trim
     * → 空则回落文件内 {@code name} → 校验长度。</p>
     *
     * <p><b>顺序有讲究</b>：必须在剥扩展名<b>之前</b>先 trim。否则
     * {@code "方案C.json  "}（尾部带空白）因末尾不是 {@code .json} 而剥不掉扩展名，
     * 方案名会变成 {@code "方案C.json"}。剥完再 trim 一次，是为了处理
     * {@code "方案C .json"} 这种扩展名前带空格的写法。</p>
     *
     * <p>文件名超长时<b>报错而非截断</b>：截断会静默改变用户看到的方案名，
     * 让用户明确改个短文件名更好。</p>
     */
    private String deriveNameFromFileName(String fileName, String fallbackName) {
        String base = fileName == null ? "" : fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.trim();
        if (base.toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION)) {
            base = base.substring(0, base.length() - JSON_EXTENSION.length());
        }
        base = base.trim();

        if (base.isEmpty()) {
            // 极少数场景（客户端未提供 filename 时为空串）才回落文件内 name
            if (fallbackName == null || fallbackName.isBlank()) {
                throw new RuntimeException(
                        "USER_CONFIG_IMPORT_INVALID: cannot derive a scheme name from file name or file content");
            }
            base = fallbackName.trim();
        }
        if (base.length() > NAME_MAX_LENGTH) {
            throw new RuntimeException("USER_CONFIG_IMPORT_INVALID: scheme name derived from file name must not exceed "
                    + NAME_MAX_LENGTH + " characters, got " + base.length());
        }
        return base;
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new RuntimeException("USER_CONFIG_INVALID: userId is required");
        }
        return userId;
    }

    private String requireConfigId(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new RuntimeException("USER_CONFIG_INVALID: configId is required");
        }
        return configId;
    }

    /** 方案名必填、非空白、≤120；顺手 trim——避免 " 方案A" 与 "方案A" 被当作两个方案 */
    private String requireName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new RuntimeException("USER_CONFIG_INVALID: name is required");
        }
        String name = rawName.trim();
        if (name.length() > NAME_MAX_LENGTH) {
            throw new RuntimeException(
                    "USER_CONFIG_INVALID: name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        return name;
    }

    private String requireConfigJson(JsonNode configJson) {
        if (configJson == null || configJson.isNull()) {
            throw new RuntimeException("USER_CONFIG_INVALID: config_json is required");
        }
        // 原样序列化落库：不解析配置内容语义
        return configJson.toString();
    }

    /**
     * 描述可选：null / 空白一律归一为 {@code null}（写库为 NULL），
     * 避免 {@code ""} 与 {@code NULL} 两种「空」语义并存；≤500。
     */
    private String normalizeDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String description = raw.trim();
        if (description.isEmpty()) {
            return null;
        }
        if (description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new RuntimeException(
                    "USER_CONFIG_INVALID: description must not exceed " + DESCRIPTION_MAX_LENGTH + " characters");
        }
        return description;
    }

    private RuntimeException nameConflict(String name, DuplicateKeyException cause) {
        return new RuntimeException(
                "USER_CONFIG_NAME_CONFLICT: a dashboard config named '" + name
                        + "' already exists for this user", cause);
    }

    private UserDashboardConfigDetailResponse toDetailResponse(UserDashboardConfig row) {
        UserDashboardConfigDetailResponse resp = new UserDashboardConfigDetailResponse();
        resp.setConfigId(row.getId());
        resp.setUserId(row.getUserId());
        resp.setName(row.getName());
        resp.setDescription(row.getDescription());
        resp.setConfigJson(parse(row.getConfigJson()));
        resp.setCreatedAt(formatTime(row.getCreatedAt()));
        resp.setUpdatedAt(formatTime(row.getUpdatedAt()));
        return resp;
    }

    private UserDashboardConfigSummaryResponse toSummaryResponse(UserDashboardConfig row) {
        UserDashboardConfigSummaryResponse resp = new UserDashboardConfigSummaryResponse();
        resp.setConfigId(row.getId());
        resp.setUserId(row.getUserId());
        resp.setName(row.getName());
        resp.setDescription(row.getDescription());
        resp.setCreatedAt(formatTime(row.getCreatedAt()));
        resp.setUpdatedAt(formatTime(row.getUpdatedAt()));
        return resp;
    }

    private String formatTime(LocalDateTime ldt) {
        return ldt == null ? null : ReplayTimeUtil.formatLocalDateTime(ldt);
    }

    /** 存量 JSON 反序列化；脏数据（非法 JSON）退化为 null，不让查询接口整体失败 */
    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("user_dashboard_config.config_json is not valid JSON, returning null: {}", e.getMessage());
            return null;
        }
    }

    /** 取文本字段；字段缺失或 JSON null 一律返回 null（而非空串），与 description 的归一化口径一致 */
    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}
