package com.isim.dims.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ErrorResponse;
import com.isim.dims.dto.UserDashboardConfigCreateRequest;
import com.isim.dims.dto.UserDashboardConfigDetailResponse;
import com.isim.dims.dto.UserDashboardConfigExportFile;
import com.isim.dims.dto.UserDashboardConfigKeyRequest;
import com.isim.dims.dto.UserDashboardConfigListRequest;
import com.isim.dims.dto.UserDashboardConfigUpdateRequest;
import com.isim.dims.security.RequireDashboardRead;
import com.isim.dims.security.RequireDashboardWrite;
import com.isim.dims.service.UserDashboardConfigService;
import com.isim.dims.util.TraceIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 用户个性化配置方案 Controller（数据回放看板）
 *
 * <p>paths（<b>全部为 POST</b>）：
 * <ul>
 *   <li>POST /v1/user-dashboard-configs/list    —— 分页查询方案列表（摘要，body: page/page_size）</li>
 *   <li>POST /v1/user-dashboard-configs/create  —— 新建方案（body: name/description/config_json）</li>
 *   <li>POST /v1/user-dashboard-configs/detail  —— 方案详情（body: config_id）</li>
 *   <li>POST /v1/user-dashboard-configs/update  —— 编辑方案，全量替换（body: config_id/name/description/config_json）</li>
 *   <li>POST /v1/user-dashboard-configs/delete  —— 删除方案，物理删除（body: config_id）</li>
 *   <li>POST /v1/user-dashboard-configs/export  —— 导出方案为 JSON 文件（body: config_id）</li>
 *   <li>POST /v1/user-dashboard-configs/import  —— 导入方案文件，multipart（仅 file）</li>
 * </ul>
 * </p>
 *
 * <p><b>方案归属用户一律取自 JWT 的 {@code sub} claim</b>，请求体里<b>没有</b>
 * {@code user_id} 字段（客户端传了也会被 Jackson 忽略）。历史上 userId 走过 URL 路径参数、
 * 也走过请求体，两种都等同于「由调用方自报身份」——任何持有写权限的用户都能把
 * {@code user_id} 换成别人的，从而读写他人方案（水平越权）。从 token 取 {@code sub}
 * 后，身份由 IAMS 签发、后端本地验签，调用方无法伪造。</p>
 *
 * <p><b>BREAKING</b>：正因如此，本组接口<b>全部要求携带有效 token</b>——
 * 即便是读接口（{@code /list}、{@code /detail}）也没有身份可取，无 token → 401
 * {@code UNAUTHORIZED}。这与项目里其它读接口「URL 层 permitAll、可匿名访问」的
 * 习惯不同，是本组接口的刻意例外：它们返回的是「我的私有配置」，必须知道「我」是谁。
 * 读接口不加 {@code @RequireDashboardRead}，只要认证通过即可访问自己的数据。</p>
 *
 * <p>响应字段为 snake_case（全局 Jackson {@code property-naming-strategy: SNAKE_CASE}）。
 * 导出接口的 JSON 序列化<b>必须使用注入的全局 ObjectMapper</b>——
 * 若自行 {@code new ObjectMapper()} 会丢失 SNAKE_CASE，导出的
 * {@code config_json} 会退化成 {@code configJson}，导入时直接解析失败。</p>
 *
 * @feature: replay
 */
@Slf4j
@RestController
@RequestMapping("/v1/user-dashboard-configs")
@RequiredArgsConstructor
public class UserDashboardConfigController {

    /** 列表请求缺省页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 列表请求缺省每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 方案列表每页条数上限，与 ExportController 保持一致 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 导出文件扩展名 */
    private static final String JSON_EXTENSION = ".json";

    /**
     * 身份缺失错误前缀。
     *
     * <p>与 {@code RestAuthenticationEntryPoint} 输出的 code 保持一致（{@code UNAUTHORIZED}），
     * 前端才能用同一套 401 逻辑处理（清 token + 跳登录），不必为「匿名访问读接口」
     * 单独写一个分支。</p>
     */
    private static final String UNAUTHORIZED_PREFIX = "UNAUTHORIZED: ";

    /**
     * 导出文件名主干长度上限。
     * 方案名允许 120 字符，但中文名 120 字 ≈ 360 字节 UTF-8，超出多数文件系统的 255 字节上限，
     * 浏览器落盘时会被截断或报错，故对外文件名先自行收口。
     */
    private static final int MAX_FILE_NAME_STEM = 100;

    /** 文件名兜底主干（方案名为空或清理后为空时使用） */
    private static final String FALLBACK_FILE_NAME_STEM = "dashboard-config";

    /**
     * 文件系统非法字符：Windows 保留字符 {@code \ / : * ? " < > |} + 控制字符。
     * 不做替换的话浏览器会静默改名（如换成下划线或直接失败），
     * 用户拿到的文件名与方案名就对不上了。
     */
    private static final Pattern ILLEGAL_FILE_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]");

    /** 文件名结尾的点与空格在 Windows 上会被静默吞掉 */
    private static final Pattern TRAILING_DOTS_OR_SPACES = Pattern.compile("[. ]+$");

    private final UserDashboardConfigService userDashboardConfigService;

    /** Spring 容器中的全局 ObjectMapper（已应用 SNAKE_CASE 命名策略） */
    private final ObjectMapper objectMapper;

    // ============ 列表 ============

    /**
     * 分页查询<b>当前登录用户</b>的方案列表（摘要，不含 config_json）
     *
     * <p>body: {@code {"page": 1, "page_size": 20}}，两者均可省略，缺省 1 / 20。</p>
     *
     * @return 200 + UserDashboardConfigListResponse（items / total / page / page_size）；
     *         无 token → 401
     */
    @PostMapping("/list")
    public ResponseEntity<?> listConfigs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) UserDashboardConfigListRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        // 缺省回落：字段缺失（null）才用默认值，显式传 0 / 负数则钳到下限，
        // 与旧版 @RequestParam(defaultValue) 的行为保持一致。
        Integer rawPage = request == null ? null : request.getPage();
        Integer rawPageSize = request == null ? null : request.getPageSize();
        int safePage = Math.max(rawPage == null ? DEFAULT_PAGE : rawPage, 1);
        int safePageSize = Math.min(
                Math.max(rawPageSize == null ? DEFAULT_PAGE_SIZE : rawPageSize, 1), MAX_PAGE_SIZE);
        try {
            String userId = resolveUserId(jwt);
            log.info("listUserDashboardConfigs request: userId={}, page={}, pageSize={}, traceId={}",
                    userId, safePage, safePageSize, traceId);
            return ResponseEntity.ok(userDashboardConfigService.list(userId, safePage, safePageSize));
        } catch (Exception e) {
            log.error("listUserDashboardConfigs failed: {}", e.getMessage(), e);
            return toError(e, "USER_CONFIG_LIST_FAILED", traceId);
        }
    }

    // ============ 新建 ============

    /**
     * 新建方案（归属当前登录用户）
     *
     * <p>body: {@code {"name": "...", "description": "...", "config_json": { ... }}}；
     * name 与 config_json 必填，description 可空。</p>
     *
     * @return 201 + 新建的方案详情；方案名重复 → 409；无 token → 401
     */
    @RequireDashboardWrite
    @PostMapping("/create")
    public ResponseEntity<?> createConfig(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UserDashboardConfigCreateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        try {
            String userId = resolveUserId(jwt);
            log.info("createUserDashboardConfig request: userId={}, name={}, traceId={}",
                    userId, request == null ? null : request.getName(), traceId);
            return ResponseEntity.status(HttpStatus.CREATED).body(userDashboardConfigService.create(
                    userId, request.getName(), request.getDescription(), request.getConfigJson()));
        } catch (Exception e) {
            log.error("createUserDashboardConfig failed: {}", e.getMessage(), e);
            return toError(e, "USER_CONFIG_CREATE_FAILED", traceId);
        }
    }

    // ============ 详情 ============

    /**
     * 查询<b>当前登录用户</b>下指定方案的详情（含 config_json）
     *
     * <p>body: {@code {"config_id": "..."}}。归属校验由 Service 层的
     * {@code (id, user_id)} 双条件完成，他人的方案 ID 一律 404（不泄露存在性）。</p>
     *
     * @return 200 + UserDashboardConfigDetailResponse；不存在或不属于当前用户 → 404；无 token → 401
     */
    @PostMapping("/detail")
    public ResponseEntity<?> getConfig(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UserDashboardConfigKeyRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        try {
            String userId = resolveUserId(jwt);
            log.info("getUserDashboardConfig request: userId={}, configId={}, traceId={}",
                    userId, request == null ? null : request.getConfigId(), traceId);
            return ResponseEntity.ok(
                    userDashboardConfigService.getDetail(userId, request.getConfigId()));
        } catch (Exception e) {
            log.error("getUserDashboardConfig failed: {}", e.getMessage(), e);
            return toError(e, "USER_CONFIG_GET_FAILED", traceId);
        }
    }

    // ============ 编辑 ============

    /**
     * 编辑方案（<b>全量替换语义</b>）
     *
     * <p>body: {@code {"config_id": "...", "name": "...",
     * "description": "...", "config_json": { ... }}}；
     * name 与 config_json 必填，description 传 {@code null} 即清空描述。</p>
     *
     * @return 200 + 更新后的方案详情；不存在或不属于当前用户 → 404；改名撞重名 → 409；无 token → 401
     */
    @RequireDashboardWrite
    @PostMapping("/update")
    public ResponseEntity<?> updateConfig(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UserDashboardConfigUpdateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        try {
            String userId = resolveUserId(jwt);
            log.info("updateUserDashboardConfig request: userId={}, configId={}, name={}, traceId={}",
                    userId, request == null ? null : request.getConfigId(),
                    request == null ? null : request.getName(), traceId);
            return ResponseEntity.ok(userDashboardConfigService.update(userId, request.getConfigId(),
                    request.getName(), request.getDescription(), request.getConfigJson()));
        } catch (Exception e) {
            log.error("updateUserDashboardConfig failed: {}", e.getMessage(), e);
            return toError(e, "USER_CONFIG_UPDATE_FAILED", traceId);
        }
    }

    // ============ 删除 ============

    /**
     * 删除方案（物理删除）
     *
     * <p>body: {@code {"config_id": "..."}}。</p>
     *
     * @return 204 No Content；不存在或不属于当前用户 → 404；无 token → 401
     */
    @RequireDashboardWrite
    @PostMapping("/delete")
    public ResponseEntity<?> deleteConfig(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UserDashboardConfigKeyRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        try {
            String userId = resolveUserId(jwt);
            log.info("deleteUserDashboardConfig request: userId={}, configId={}, traceId={}",
                    userId, request == null ? null : request.getConfigId(), traceId);
            userDashboardConfigService.delete(userId, request.getConfigId());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("deleteUserDashboardConfig failed: {}", e.getMessage(), e);
            return toError(e, "USER_CONFIG_DELETE_FAILED", traceId);
        }
    }

    // ============ 导出 ============

    /**
     * 导出方案为 JSON 文件（供同事导入，达成配置分享）
     *
     * <p>body: {@code {"config_id": "..."}}；
     * 返回 {@code Content-Disposition: attachment}，文件名为 {@code <方案名>.json}。</p>
     *
     * <p><b>前端注意</b>：因为用了 POST，不能用 {@code window.open} / {@code <a href>} 触发下载，
     * 必须走 {@code axios(responseType:'blob')} 再手动构造 {@code URL.createObjectURL} 下载，
     * 并从响应头解析文件名。</p>
     *
     * <p>返回类型刻意用 {@code ResponseEntity<?>} + {@code byte[]} 而非
     * {@code StreamingResponseBody}：配置 JSON 体积很小（KB 级），无需流式；
     * 而 {@code ResponseEntity<?>} 会在 {@code StreamingResponseBodyReturnValueHandler} 处失配
     * 导致 500（见 {@code ExportController#downloadExport} 的注释），
     * 用 byte[] 才能让同一个方法既返回文件又能返回 {@link ErrorResponse}。</p>
     *
     * @return 200 + application/json 文件流；不存在或不属于当前用户 → 404；无 token → 401
     */
    @RequireDashboardRead
    @PostMapping("/export")
    public ResponseEntity<?> exportConfig(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UserDashboardConfigKeyRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        try {
            String userId = resolveUserId(jwt);
            log.info("exportUserDashboardConfig request: userId={}, configId={}, traceId={}",
                    userId, request == null ? null : request.getConfigId(), traceId);
            UserDashboardConfigExportFile file =
                    userDashboardConfigService.export(userId, request.getConfigId());
            // 必须用注入的全局 ObjectMapper：自建实例不带 SNAKE_CASE，
            // 会让 config_json 变成 configJson 并导致该文件无法再被导入。
            byte[] body = objectMapper.writeValueAsBytes(file);
            String fileName = buildExportFileName(file.getName());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(fileName, StandardCharsets.UTF_8)
                    .build());
            headers.setContentLength(body.length);
            return ResponseEntity.ok().headers(headers).body(body);
        } catch (Exception e) {
            log.error("exportUserDashboardConfig failed: {}", e.getMessage(), e);
            return toError(e, "USER_CONFIG_EXPORT_FAILED", traceId);
        }
    }

    // ============ 导入 ============

    /**
     * 导入方案文件（multipart），方案归属当前登录用户，方案名默认取文件名
     *
     * <p>form-data 只需 {@code file=<xxx.json>} —— <b>没有 {@code user_id} 字段</b>：
     * 归属用户来自 token，导入到别人名下这件事在协议层就不存在。
     * 文件名去掉 {@code .json} 后作为方案名；名称重复 → 409。</p>
     *
     * @return 201 + 导入后的方案详情；文件非法 → 400；名称重复 → 409；无 token → 401
     */
    @RequireDashboardWrite
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importConfig(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String rawTraceId) {
        String traceId = TraceIdUtil.resolve(rawTraceId);
        String originalFileName = file == null ? null : file.getOriginalFilename();
        try {
            String userId = resolveUserId(jwt);
            log.info("importUserDashboardConfig request: userId={}, fileName={}, size={}, traceId={}",
                    userId, originalFileName, file == null ? -1 : file.getSize(), traceId);
            byte[] content = file.getBytes();
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    userDashboardConfigService.importConfig(userId, originalFileName, content));
        } catch (Exception e) {
            log.error("importUserDashboardConfig failed: {}", e.getMessage(), e);
            return toError(e, "USER_CONFIG_IMPORT_FAILED", traceId);
        }
    }

    // ============ 私有辅助 ============

    /**
     * 取当前登录用户身份 —— <b>本组接口唯一的身份来源</b>。
     *
     * <p>URL 层是 {@code anyRequest().permitAll()}，所以匿名请求也能进到这里，
     * {@code @AuthenticationPrincipal} 会注入 {@code null}（匿名身份的 principal 是
     * {@code String "anonymousUser"}，与 {@link Jwt} 类型不匹配）。
     * 因此这里显式判空并抛 401，而不是把 {@code null} 当成 userId 往下传——
     * 后者会变成「查询 {@code user_id IS NULL}」，静默返回空列表，看起来一切正常。</p>
     */
    private String resolveUserId(Jwt jwt) {
        if (jwt == null) {
            throw new RuntimeException(UNAUTHORIZED_PREFIX + "missing or invalid token");
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            // IAMS 必然签发 sub；缺失说明 token 本身不合规，靠重登解决，故同样归 401
            throw new RuntimeException(UNAUTHORIZED_PREFIX + "token subject (sub) claim is missing");
        }
        return sub.trim();
    }

    /**
     * 由方案名生成导出文件名。
     *
     * <p>非法字符替换为 {@code _}（而非直接报错——导出不该因为方案名里有个
     * {@code /} 就失败），并收口长度。方案名本身不做修改，落库值保持原样。</p>
     *
     * <p>刻意声明为 package-private static：清理规则是本接口唯一的外部契约细节
     * （浏览器拿到的文件名），透过 HTTP 头做断言会依赖 Spring 的
     * {@code ContentDisposition} 编码实现（ASCII 直出 / {@code filename*} RFC 5987 两条分支），
     * 断言脆弱。直接单测纯函数更精确。</p>
     */
    static String buildExportFileName(String name) {
        String stem = name == null ? "" : name.trim();
        stem = ILLEGAL_FILE_NAME_CHARS.matcher(stem).replaceAll("_");
        stem = TRAILING_DOTS_OR_SPACES.matcher(stem).replaceAll("");
        if (stem.isEmpty()) {
            stem = FALLBACK_FILE_NAME_STEM;
        }
        if (stem.length() > MAX_FILE_NAME_STEM) {
            stem = stem.substring(0, MAX_FILE_NAME_STEM);
        }
        return stem + JSON_EXTENSION;
    }

    /**
     * 统一错误分流。
     *
     * <p>Service 抛 {@code RuntimeException("CODE: detail")}，按前缀映射 HTTP 状态码：
     * 401 / 400 / 404 / 409 各自的前缀，其余落到 500 + {@code fallbackCode}。</p>
     *
     * <p>说明：这里刻意用「前缀分流」而不是像 {@code SceneRuleController} 那样在方法内
     * {@code catch (DuplicateKeyException)} —— 重名冲突由 Service 层翻译为领域错误码
     * （它才知道撞的是 {@code uk_user_dashboard_config_user_name}），
     * 且本 Controller 已有统一分流出口，避免 4 个写方法各复制一遍 catch 块。</p>
     *
     * <p>{@code UNAUTHORIZED} 分支输出的 code / message 与
     * {@code RestAuthenticationEntryPoint} 对齐（后者管「受 {@code @Require*} 保护的
     * 方法被匿名访问」，本分支管「读接口被匿名访问」），使前端只需一套 401 处理。</p>
     */
    private ResponseEntity<ErrorResponse> toError(Exception e, String fallbackCode, String traceId) {
        String msg = e.getMessage();
        if (msg != null && msg.startsWith(UNAUTHORIZED_PREFIX)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(
                    "UNAUTHORIZED",
                    "Authentication required: " + msg.substring(UNAUTHORIZED_PREFIX.length()),
                    traceId));
        }
        if (msg != null && msg.startsWith("USER_CONFIG_INVALID:")) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("USER_CONFIG_INVALID", msg, traceId));
        }
        if (msg != null && msg.startsWith("USER_CONFIG_IMPORT_INVALID:")) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("USER_CONFIG_IMPORT_INVALID", msg, traceId));
        }
        if (msg != null && msg.startsWith("USER_CONFIG_NOT_FOUND:")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of("USER_CONFIG_NOT_FOUND", msg, traceId));
        }
        if (msg != null && msg.startsWith("USER_CONFIG_NAME_CONFLICT:")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("USER_CONFIG_NAME_CONFLICT", msg, traceId));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(fallbackCode, msg != null ? msg : e.toString(), traceId));
    }
}
