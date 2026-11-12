package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.*;
import com.isim.dims.entity.*;
import com.isim.dims.exception.StorageException;
import com.isim.dims.mapper.*;
import com.isim.dims.service.CfdiPipelineService;
import com.isim.dims.service.DataSliceService;
import com.isim.dims.service.ImageSearchService;
import com.isim.dims.service.IngestionPipelineService;
import com.isim.dims.service.SimsTagCatalog;
import com.isim.dims.service.StorageStat;
import com.isim.dims.service.log.LocalLogFileStore;
import com.isim.dims.service.log.LogFileStore;
import com.isim.dims.service.log.LogPath;
import com.isim.dims.service.log.NfsLogFileStore;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据切片 Service 实现
 * <p>
 * 核心业务逻辑：
 * 1. upload：保存文件 → 创建记录 → 委派异步解析流水线
 * 2. search：多条件检索（含时间、GPS、传感器、标签、排序过滤）→ Entity→DTO 转换
 * 3. getDetail：切片详情 + SensorStream 展开
 * 4. delete：文件清理 + 级联删除 + Kafka 事件日志
 *
 * @feature: slice
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSliceServiceImpl implements DataSliceService {

    private final DataSliceMapper dataSliceMapper;
    private final SensorStreamMapper sensorStreamMapper;
    private final DataSliceTagMapper dataSliceTagMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionTaskStatusHistoryMapper ingestionTaskStatusHistoryMapper;
    private final SimsTagCatalog simsTagCatalog;
    private final IngestionPipelineService ingestionPipelineService;
    private final CfdiPipelineService cfdiPipelineService;
    private final CameraCalibrationMapper cameraCalibrationMapper;
    private final CfdiMetaMapper cfdiMetaMapper;
    private final SliceStatsMapper sliceStatsMapper;
    private final VinMd5MappingMapper vinMd5MappingMapper;
    private final StorageAccess storageAccess;
    private final SceneExtractionTaskMapper sceneExtractionTaskMapper;
    private final ImageSearchService imageSearchService;
    private final FilterOptionProvider filterOptionProvider;
    private final VectorAssetCleanupService vectorAssetCleanupService;

    /**
     * 日志下载允许读取的根目录（逗号分隔）。{@code data_slice.import_source_dir} 必须落在其中
     * 之一，否则 403。路径来自本系统自己的库表而非用户输入，这层白名单是防御性兜底
     * （防脏数据把任意系统路径读出去），不是主要安全边界。
     *
     * <p>⚠️ 现网同时存在两个根：{@code /data/downloads/...} 与
     * {@code /data/jiangling-MMT-CX835-ICA-back/...}，所以默认取二者共同父级 {@code /data}，
     * 换环境（如 NFS 挂到 {@code /mnt/xxx}）必须显式覆盖。
     */
    @Value("${dims.download.log.allowed-roots:/data}")
    private String logAllowedRoots;

    /**
     * 单次日志下载的总大小上限（字节），超过直接拒绝而不是硬拉。默认 50MB。
     *
     * <p>日志通常只有 KB~MB 级；卡片体量异常通常意味着「日志口径选错了」（把 bag 一起算进来了），
     * 此时应当报错让调用方看见，而不是让浏览器默默拉几百 MB。
     */
    @Value("${dims.download.log.max-total-bytes:52428800}")
    private long logMaxTotalBytes;

    /**
     * 逻辑路径 → 实际 IO 路径的前缀映射（{@code 源>目标}，逗号分隔）。默认空 = 不做映射。
     *
     * <p>用途：库表记的是服务器/容器里的绝对路径（{@code /data/downloads}），而运行进程看到的
     * 挂载点可能不同 —— NFS 挂到别的前缀、或在非 Linux 机器上调试（{@code /data} 会解析成
     * 当前盘符下的 {@code D:\data}）。
     *
     * <p>⚠️ 白名单校验用的是<b>逻辑路径</b>（映射由部署方配置，不是攻击面），
     * 所以映射的目标不需要也不应该写进 {@code allowed-roots}。
     */
    @Value("${dims.download.log.path-mappings:}")
    private String logPathMappings;

    /**
     * 目录名「等于」其一即整目录算日志，默认 {@code log,logs}；置空 = 不按目录名收。
     */
    @Value("${dims.download.log.dir-names:log,logs}")
    private String logDirNames;

    /**
     * 目录名「包含」其一即整目录算日志，默认 {@code forever_log}（平台侧周期日志包目录名形如 {@code <ts>_forever_log_<car>}）；置空 = 不收这类目录。
     */
    @Value("${dims.download.log.dir-name-contains:forever_log}")
    private String logDirNameContains;

    /**
     * 与日志目录同级、需要一并下载的元数据文件名（逗号分隔）。默认 {@code meta,log_info.json}。
     *
     * <p>为什么必须带上：现网真实布局是
     * {@code <session>/public/<bundle>/log/*.gz} 与同级的 {@code <bundle>/meta}、{@code log_info.json}
     * —— {@code meta} 是这次采集的元信息（包名/触发时间/版本），{@code log_info.json} 是日志清单
     * （有哪些日志、时间范围）。只有日志没有它们，拿到手没法判断「这份日志对应哪次采集」。
     * 两个文件合计约 5KB，代价可忽略。置空 = 不额外收元数据。
     */
    @Value("${dims.download.log.meta-names:meta,log_info.json}")
    private String logMetaNames;

    /**
     * 日志读取来源：{@code auto}（默认，先试本地再退 NFS）/ {@code local} / {@code nfs}。
     *
     * <p>库表记的是服务器视角的 {@code /data/downloads/...}，而进程未必看得见这个路径
     * （pod 里没挂 NFS、或本地调试时 {@code /data} 被解析成 {@code D:\data}），
     * 此时必须按 NFS 协议直连。{@code auto} 让同一份配置在两种部署下都能跑。
     */
    @Value("${dims.download.log.source:auto}")
    private String logSourceMode;

    /**
     * 日志收集范围：{@code session}（默认，只扫 {@code import_source_dir} 指向的那个会话目录）
     * 或 {@code md5-dir}（上提一层，把同一 md5 下<b>所有</b>会话目录的日志一起收）。
     *
     * <p>为什么留这个开关：同一 md5 下现网确实存在多个会话目录
     * （实测 {@code 096b6130.../} 下有 {@code ..._9984/_9985/_9986_public} 三个），
     * 而切片是按会话逐条入库的。默认只收本切片的会话（语义清晰、体积可控）；
     * 需要「把这一批的日志全打包」时切成 {@code md5-dir}。
     */
    @Value("${dims.download.log.scope:session}")
    private String logScope;

    /**
     * 逻辑路径 → NFS 内路径的前缀：库表 {@code /data/...} 对应服务端导出的
     * {@code /nfs/dims/...}，所以去掉 {@code /data} 前缀后剩下的 {@code /downloads/...}
     * 就是导出根之下的路径。不以该前缀开头时跳过 NFS 来源并如实报错，不猜路径。
     */
    @Value("${dims.download.log.nfs.strip-prefix:/data}")
    private String nfsStripPrefix;

    /**
     * 日志文件口径：文件名形如 {@code *.log} / {@code *.log.gz} / {@code x.log.2026-08-19_15:07:08.gz} / {@code CM_ROUTING.log_4.gz}。
     */
    private static final Pattern LOG_FILE_NAME = Pattern.compile("(?i).*\\.log([._].*)?$");

    /**
     * Windows 盘符路径被 {@link LogPath} 拆段后渲染成 {@code /C:/...}，本地化前需还原。
     */
    private static final Pattern DRIVE_LETTER_PATH = Pattern.compile("^/[A-Za-z]:(/.*)?$");

    /**
     * 切片名第 2 段作为车型代码的合法字符集（如 {@code CX835ICA}、{@code 835BVP4-15}）；
     * 纯数字（时间戳）不算车型。
     */
    private static final Pattern MODEL_CODE_PATTERN = Pattern.compile("[A-Za-z0-9.\\-]+");

    /**
     * 平台簿记文件：不是日志内容，一律排除。
     *
     * <p>实证（NFS 上真实会话目录 {@code <bundle>/}）：{@code upload.done}（40117B 的流水）、
     * {@code dump_finish}（11B）、{@code good_flag}（9B）、{@code filter_meta}（2703B）。
     * 注意 {@code meta} / {@code log_info.json} <b>不在</b>此列 —— 它们是本次要下载的元数据，
     * 由 {@code meta-names} 单独控制。
     */
    private static final Set<String> NON_LOG_FILE_NAMES = Set.of(
            "upload.done", "download.done", "dump_finish", "good_flag", "filter_meta");

    /**
     * 「路径不对」时回给调用方的目录内容摘要条数上限（避免异常消息过长）。
     */
    private static final int LOG_DIR_PREVIEW_LIMIT = 20;

    /**
     * 递归扫描的深度与节点预算上限：防御异常目录树（软链环、超深目录）把请求拖死。
     */
    private static final int LOG_SCAN_MAX_DEPTH = 12;
    private static final int LOG_SCAN_MAX_ENTRIES = 20000;

    /**
     * 本地文件系统通道：无状态，直接 new（不进构造参数，避免为一个无依赖的类加参数）。
     */
    private final LogFileStore localStore = new LocalLogFileStore();

    /**
     * NFS 通道：需要 {@code @Value} 配置，交给 Spring；单测里为 null 时自动跳过 NFS 来源。
     */
    @Autowired
    private NfsLogFileStore nfsStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * ORDER BY 白名单——防止 SQL 注入（C-5）
     *
     * <p>"preset" 为特殊值：按请求传入 slice_ids 的顺序返回，用于保留
     * 语义检索（以文搜图 / 以图搜图）的 Reranker 得分排序。见
     * {@link #validateSort(String)} 与 DataSliceMapper.xml 的 PRESET 分支。</p>
     */
    private static final Set<String> ALLOWED_SORT_COLUMNS = Set.of(
            "created_at", "start_ts", "end_ts", "trigger_time", "file_size", "name", "event_name",
            "preset"
    );

    /**
     * 排序方向白名单
     */
    private static final Set<String> ALLOWED_SORT_DIRECTIONS = Set.of("ASC", "DESC");

    /**
     * MySQL DATETIME 兼容格式（ISO_LOCAL_DATE_TIME 的 T → 空格）
     */
    private static final DateTimeFormatter MYSQL_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ========================================================================
    // 上传导入
    // ========================================================================

    @Override
    @Transactional
    public Map<String, String> upload(MultipartFile file, String name, String source) {
        // 1. 校验文件扩展名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new RuntimeException("UPLOAD_FAILED: file name is empty");
        }

        boolean isCfdi = originalFilename.toLowerCase().endsWith(".tar.gz")
                || originalFilename.toLowerCase().endsWith(".tgz");
        boolean isBag = originalFilename.toLowerCase().endsWith(".bag");

        if (!isCfdi && !isBag) {
            throw new RuntimeException(
                    "UPLOAD_FAILED: unsupported file extension. Only .bag and .tar.gz are accepted");
        }

        // 2. 生成切片 UUID
        String sliceId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();

        // 3. 确定名称
        String sliceName = (name != null && !name.isBlank())
                ? name
                : extractFileName(originalFilename);

        // 4. 数据来源提供方（前端传参，枚举校验在 Controller 完成）
        // 5. 保存文件（local 模式 → 本地磁盘；minio 模式 → 对象存储）
        String storageKey = isCfdi ? StorageKeys.originalTarGz(sliceId) : StorageKeys.originalBag(sliceId);
        try (InputStream in = file.getInputStream()) {
            storageAccess.put(storageKey, in, file.getSize(),
                    isCfdi ? "application/gzip" : "application/octet-stream");
        } catch (IOException e) {
            log.error("Failed to save file for sliceId={}", sliceId, e);
            throw new RuntimeException("UPLOAD_FAILED", e);
        }

        // 6. 创建 DataSlice 记录
        DataSlice dataSlice = new DataSlice();
        dataSlice.setId(sliceId);
        dataSlice.setName(sliceName);
        dataSlice.setFilePath(storageKey);
        dataSlice.setStorageType(storageAccess.isMinioMode() ? "MINIO" : "LOCAL");
        dataSlice.setFileSize(file.getSize());
        dataSlice.setStatus("UPLOADING");
        dataSlice.setSourceType(source);
        dataSliceMapper.insert(dataSlice);

        // 7. 创建 IngestionTask 记录
        IngestionTask task = new IngestionTask();
        task.setId(taskId);
        task.setSliceId(sliceId);
        task.setStatus("UPLOADING");
        task.setProgress(0);
        ingestionTaskMapper.insert(task);

        // 记录初始 UPLOADING 状态变更历史
        IngestionTaskStatusHistory history = new IngestionTaskStatusHistory();
        history.setTaskId(taskId);
        history.setStatus("UPLOADING");
        history.setProgress(0);
        ingestionTaskStatusHistoryMapper.insert(history);

        // 8. 委派异步流水线
        if (isCfdi) {
            cfdiPipelineService.processCfdiPipeline(sliceId, storageKey);
        } else {
            ingestionPipelineService.processParsePipeline(sliceId, storageKey);
        }

        // 9. 返回结果
        log.info("Upload created: sliceId={}, taskId={}, name={}, source={}",
                sliceId, taskId, sliceName, source);

        Map<String, String> result = new HashMap<>();
        result.put("slice_id", sliceId);
        result.put("task_id", taskId);
        result.put("status", "UPLOADING");
        return result;
    }

    // ========================================================================
    // 多条件检索（分页）
    // ========================================================================

    /**
     * 把请求里的 {@code filter}（筛选器名）展开成 {@code eventNames}（事件名列表）。
     *
     * <p>前端「触发类型」下拉与智驾事件看板的筛选器下拉同源，送的是筛选器名
     * （{@code camera_event_config.json} 的 {@code events.*.filter}），库里存的却是
     * {@code event_name}，且一个筛选器可能对应多个事件（如 {@code highbeam_on_filter} →
     * 高中/中远光）→ 展开成列表交给 SQL 取并集。展开走
     * {@link FilterOptionProvider#resolveEventNames(String)}，与下拉选项同一份配置，
     * 保证「下拉里能选到的」与「后端能查到的」永远对得上。</p>
     *
     * <p>边界：显式传了 {@code event_name} 时以它为准并忽略 filter（兼容既有调用方）；
     * filter 未命中配置时由 Provider 兜底成 {@code [filter]}，结果是空集而非全量。</p>
     */
    private void applyFilterExpansion(SliceSearchRequest request) {
        String filter = request.getFilter();
        if (filter == null || filter.isBlank()) {
            return;
        }
        if (request.getEventName() != null && !request.getEventName().isBlank()) {
            log.debug("filter={} 被忽略：请求已显式指定 event_name={}", filter, request.getEventName());
            return;
        }
        List<String> events = filterOptionProvider.resolveEventNames(filter);
        request.setEventNames(events);
        log.info("filter {} 展开为事件名 {}（{} 个）", filter.trim(), events, events.size());
    }

    /**
     * 功能筛选的合法功能名（与 slice_stats 布尔冗余列一一对应）
     */
    private static final Set<String> ALLOWED_FEATURES = Set.of(
            "NOA", "AEB", "APA", "LCC", "ACC", "ROAMING", "AVP", "FCW", "AES", "ESS");

    /**
     * 解析功能激活筛选参数 {@code features}（逗号分隔，多选 AND 语义）。
     *
     * <p>大小写不敏感，"LCC+" 等价 "LCC"；无法识别的 token 直接忽略。
     * 入参非空但没有任何合法 token 时：条件不可能命中 → 返回 {@code false}，
     * 调用方返回空结果，绝不能静默丢掉条件退化成「不过滤」（与 tag 关键词无匹配的处理一致）。</p>
     *
     * @return true = 继续正常检索；false = 无可能命中，调用方直接返回空结果
     */
    private boolean applyFeatureFilter(SliceSearchRequest request) {
        String features = request.getFeatures();
        if (features == null || features.isBlank()) {
            return true;
        }
        List<String> names = Arrays.stream(features.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace("+", "").toUpperCase(Locale.ROOT))
                .filter(ALLOWED_FEATURES::contains)
                .distinct()
                .collect(Collectors.toList());
        if (names.isEmpty()) {
            log.debug("features=[{}] 无合法功能名，返回空结果", features);
            return false;
        }
        request.setFeatureNames(names);
        log.debug("features=[{}] 解析为 {}（AND 语义）", features, names);
        return true;
    }

    /**
     * 把前端传入的时间范围边界（RFC3339）转成 MySQL DATETIME 字符串（UTC 墙钟）。
     *
     * <p>{@code data_slice.trigger_time} 落库为 UTC（与 start_ts / end_ts 语义一致），
     * 前端按东八区（北京时间）展示，因此选择器回传的是带本地偏移的时间，如
     * {@code 2026-10-15T10:00:00+08:00}。必须先换算成同一时刻的 UTC 墙钟
     * （{@code 2026-10-15 02:00:00}）再与库里比较 —— 直接 {@code toLocalDateTime()}
     * 会丢掉偏移，导致筛选结果整体偏移 8 小时。</p>
     *
     * <p>兼容无偏移写法（{@code 2026-10-15T10:00:00}）：按历史约定视为已是 UTC，原值返回。</p>
     *
     * @param raw       前端传入的时间字符串
     * @param paramName 参数名（仅用于日志）
     * @return MySQL 兼容格式字符串；解析失败返回 {@code null}（调用方保留原值）
     */
    private String toUtcMysqlDateTime(String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            OffsetDateTime odt = OffsetDateTime.parse(value);
            return odt.withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .format(MYSQL_DATETIME_FORMAT);
        } catch (DateTimeParseException e) {
            // 无偏移量：按历史约定视为已经是 UTC
            try {
                return LocalDateTime.parse(value).format(MYSQL_DATETIME_FORMAT);
            } catch (DateTimeParseException e2) {
                log.warn("Failed to parse {}: {}, using raw value", paramName, raw);
                return null;
            }
        }
    }

    @Override
    public SliceListResponse search(SliceSearchRequest request) {
        // ---- C-3: 将 RFC3339 字符串转为 MySQL 兼容格式 ----
        if (request.getStartTs() != null && !request.getStartTs().isEmpty()) {
            OffsetDateTime odt = OffsetDateTime.parse(request.getStartTs());
            request.setStartTs(odt.toLocalDateTime().format(MYSQL_DATETIME_FORMAT));
        }
        if (request.getEndTs() != null && !request.getEndTs().isEmpty()) {
            OffsetDateTime odt = OffsetDateTime.parse(request.getEndTs());
            request.setEndTs(odt.toLocalDateTime().format(MYSQL_DATETIME_FORMAT));
        }

        // ---- 处理 upload date 范围（start_date / end_date → created_at 范围） ----
        if (request.getStartDate() != null && !request.getStartDate().isEmpty()) {
            try {
                OffsetDateTime odt = OffsetDateTime.parse(request.getStartDate());
                request.setStartDate(odt.toLocalDateTime().format(MYSQL_DATETIME_FORMAT));
            } catch (Exception e) {
                log.warn("Failed to parse start_date: {}, using raw value", request.getStartDate());
            }
        }
        if (request.getEndDate() != null && !request.getEndDate().isEmpty()) {
            try {
                OffsetDateTime odt = OffsetDateTime.parse(request.getEndDate());
                request.setEndDate(odt.toLocalDateTime().format(MYSQL_DATETIME_FORMAT));
            } catch (Exception e) {
                log.warn("Failed to parse end_date: {}, using raw value", request.getEndDate());
            }
        }

        // ---- 处理触发时间范围（trigger_start / trigger_end → trigger_time 范围） ----
        // 前端按北京时间（东八区）选择，回传带 +08:00 偏移的 RFC3339；库里 trigger_time 为 UTC，
        // 必须换算成同一时刻的 UTC 墙钟再比较，否则整体偏 8 小时。
        if (request.getTriggerStart() != null && !request.getTriggerStart().isEmpty()) {
            String utc = toUtcMysqlDateTime(request.getTriggerStart(), "trigger_start");
            if (utc != null) {
                request.setTriggerStart(utc);
            }
        }
        if (request.getTriggerEnd() != null && !request.getTriggerEnd().isEmpty()) {
            String utc = toUtcMysqlDateTime(request.getTriggerEnd(), "trigger_end");
            if (utc != null) {
                request.setTriggerEnd(utc);
            }
        }

        // ---- 筛选器（filter）→ 事件名列表展开 ----
        applyFilterExpansion(request);

        // ---- 功能激活筛选（features=NOA,AEB,...）→ 规范功能名列表 ----
        // false = 入参非空但无合法功能名，条件不可能命中 → 直接返回空结果
        if (!applyFeatureFilter(request)) {
            SliceListResponse empty = new SliceListResponse();
            empty.setItems(new ArrayList<>(0));
            empty.setTotal(0L);
            empty.setPage(request.getPage());
            empty.setPageSize(request.getPageSize());
            return empty;
        }

        // ---- 标签名关键词 → tag_id 集合 ----
        // tag 表已随 V29 移除（标签定义在 SIMS），SQL 无法再 JOIN tag 按名字过滤；
        // 用 SimsTagCatalog 词表在内存里做模糊匹配，把关键词解析成 id 集合传给 SQL。
        // 解析不到任何 id：不可能有切片满足该条件 → 直接返回空结果，
        // 绝不能把条件静默丢掉（那会退化成「不过滤」，返回全部切片）。
        if (request.getTag() != null && !request.getTag().isBlank()) {
            String kw = request.getTag().trim().toLowerCase();
            List<String> matchedIds = simsTagCatalog.all().stream()
                    .filter(ref -> ref.getName() != null
                            && ref.getName().toLowerCase().contains(kw))
                    .map(TagRef::getId)
                    .toList();
            if (matchedIds.isEmpty()) {
                log.debug("标签关键词 [{}] 在 SIMS 词表中无匹配，返回空结果", request.getTag());
                SliceListResponse empty = new SliceListResponse();
                empty.setItems(new ArrayList<>(0));
                empty.setTotal(0L);
                empty.setPage(request.getPage());
                empty.setPageSize(request.getPageSize());
                return empty;
            }
            request.setTagIds(matchedIds);
        }

        // ---- C-4: GPS 中心点 + 半径 → 包围盒转换 ----
        if (request.getLat() != null && request.getLon() != null && request.getRadius() != null) {
            double latDelta = request.getRadius() / 111320.0;
            double lonDelta = request.getRadius() / (111320.0 * Math.cos(Math.toRadians(request.getLat())));
            request.setMinLat(request.getLat() - latDelta);
            request.setMaxLat(request.getLat() + latDelta);
            request.setMinLon(request.getLon() - lonDelta);
            request.setMaxLon(request.getLon() + lonDelta);
        }

        // ---- C-5: 排序参数白名单校验 ----
        request.setSort(validateSort(request.getSort()));

        // ---- C-6: 图像语义检索（以文搜图）→ sliceId 集合过滤 ----
        // 仅 text 模式在此处理：以图搜图 / 特征搜图由前端先得到 sliceId 集合，
        // 通过 slice_ids 参数传入，作为通用过滤条件。
        resolveImageSearch(request);

        SliceListResponse response = new SliceListResponse();

        // 搜图模式已启用但无任何匹配切片 → 直接返回空列表
        if (isImageSearchRequested(request) && (request.getSliceIds() == null || request.getSliceIds().isEmpty())) {
            response.setItems(new ArrayList<>(0));
            response.setTotal(0L);
            response.setPage(request.getPage());
            response.setPageSize(request.getPageSize());
            return response;
        }

        // 3. 计算总数（使用 selectCount 近似统计，复杂过滤条件由 searchSlices 精确匹配）
        Long total = estimateTotal(request);
        if (total <= 0) {
            response.setItems(new ArrayList<>(0));
            response.setTotal(total);
            response.setPage(request.getPage());
            response.setPageSize(request.getPageSize());
            return response;
        }

        // 1. 执行多条件检索（MyBatis-Plus Page 插件在 XML 中处理 LIMIT/OFFSET）
        List<DataSlice> entities = dataSliceMapper.searchSlices(request);

        // 2. 转换 Entity → SliceItem
        List<SliceListResponse.SliceItem> items = entities.stream()
                .map(this::toSliceItem)
                .collect(Collectors.toList());

        // 3. 车型兜底：无 slice_stats 的来源（如 Hirain）按 VIN 反查 vin_md5_mapping.model_code
        fillVehicleTypeByVin(items);

        // 4. 组装响应
        response.setItems(items);
        response.setTotal(total);
        response.setPage(request.getPage());
        response.setPageSize(request.getPageSize());

        return response;
    }

    // ========================================================================
    // 切片详情
    // ========================================================================

    @Override
    public SliceDetailResponse getDetail(String sliceId) {
        // 1. 查询切片
        DataSlice entity = dataSliceMapper.selectById(sliceId);
        if (entity == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 2. 查询关联的 SensorStream 列表
        List<SensorStream> streams = sensorStreamMapper.selectBySliceId(sliceId);

        // 2.5 查询关联的 slice_stats（1:1，无统计记录时为 null）
        SliceStats stats = sliceStatsMapper.selectOne(
                new LambdaQueryWrapper<SliceStats>().eq(SliceStats::getSliceId, sliceId)
        );

        // 3. 查询标签名（本地 tag 表已随 V29 移除，名称从 SIMS 词表缓存解析）
        List<String> tagNames = resolveTagNames(dataSliceTagMapper.selectBySliceId(sliceId));

        // 4. 组装响应
        return toSliceDetailResponse(entity, streams, tagNames, stats);
    }

    // ========================================================================
    // 删除切片
    // ========================================================================

    @Override
    @Transactional
    public void delete(String sliceId) {
        // 1. 查询切片
        DataSlice entity = dataSliceMapper.selectById(sliceId);
        if (entity == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 2. 删除原始文件（LOCAL 来源的 file_path 在转换完成前指向源会话目录，
        //    目录不删——删除会破坏用户原始数据；文件/相对 key 正常删除）
        String filePath = entity.getFilePath();
        if (filePath != null && !filePath.isBlank()) {
            try {
                storageAccess.delete(filePath);
                log.debug("Deleted slice file: {}", filePath);
            } catch (StorageException e) {
                log.warn("Failed to delete slice file: {}", filePath, e);
            }
        }

        // 3. 清空关联导出产物（目录语义：仅删内容）
        try {
            storageAccess.deleteByPrefix(StorageKeys.exportPrefix(sliceId));
            log.debug("Cleaned export products for sliceId={}", sliceId);
        } catch (StorageException e) {
            log.warn("Failed to clean export products for sliceId={}", sliceId, e);
        }

        // 3.5 清空回放产物缓存（2D Replay：障碍分段/消息 JSONL，切片删除级联清理）
        try {
            storageAccess.deleteByPrefix(StorageKeys.replayPrefix(sliceId));
            log.debug("Cleaned replay cache for sliceId={}", sliceId);
        } catch (StorageException e) {
            log.warn("Failed to clean replay cache for sliceId={}", sliceId, e);
        }

        // 3.6 清空视频产物（videos/{id}：11 路 h265+mp4，体量最大）
        try {
            storageAccess.deleteByPrefix(StorageKeys.videosPrefix(sliceId));
            log.debug("Cleaned videos for sliceId={}", sliceId);
        } catch (StorageException e) {
            log.warn("Failed to clean videos for sliceId={}", sliceId, e);
        }

        // 3.7 清空场景提取产物（scene_data/{id}：感知分段/OpenSCENARIO 等）
        try {
            storageAccess.deleteByPrefix(StorageKeys.sceneDataPrefix(sliceId));
            log.debug("Cleaned scene_data for sliceId={}", sliceId);
        } catch (StorageException e) {
            log.warn("Failed to clean scene_data for sliceId={}", sliceId, e);
        }

        // 3.8 清空驾驶舱统计产物（cockpit/{id}：cockpit_stats.json）
        try {
            storageAccess.deleteByPrefix(StorageKeys.cockpitPrefix(sliceId));
            log.debug("Cleaned cockpit stats for sliceId={}", sliceId);
        } catch (StorageException e) {
            log.warn("Failed to clean cockpit stats for sliceId={}", sliceId, e);
        }

        // 3.9 清空图片
        try {
            storageAccess.deleteByPrefix(StorageKeys.videoImagesPrefix(sliceId));
            log.debug("Cleaned img stats for sliceId={}", sliceId);
        } catch (StorageException e) {
            log.warn("Failed to clean img stats for sliceId={}", sliceId, e);
        }

        // tasks/{taskId}.json 元数据按 taskId 键控（无 slice 索引，逐文件扫描代价 O(N) 且
        // delete 处于 @Transactional 内不宜长事务），不在此清理 —— 由
        // VisualizationPipelineServiceImpl.cleanupDiskMetadata 的 diskRetentionHours
        // 保留期兜底自动过期删除。

        // 4. 级联删除数据库记录
        try {
            LambdaQueryWrapper<SensorStream> streamWrapper = new LambdaQueryWrapper<>();
            streamWrapper.eq(SensorStream::getSliceId, sliceId);
            sensorStreamMapper.delete(streamWrapper);

            LambdaQueryWrapper<DataSliceTag> tagWrapper = new LambdaQueryWrapper<>();
            tagWrapper.eq(DataSliceTag::getSliceId, sliceId);
            dataSliceTagMapper.delete(tagWrapper);

            LambdaQueryWrapper<IngestionTask> taskWrapper = new LambdaQueryWrapper<>();
            taskWrapper.eq(IngestionTask::getSliceId, sliceId);
            ingestionTaskMapper.delete(taskWrapper);
        } catch (Exception e) {
            log.warn("Failed to cascade delete related records for sliceId={}", sliceId, e);
        }

        dataSliceMapper.deleteById(sliceId);

        // 4.1 级联清理 pgvector 向量库关联数据（image_asset 帧级 + video_clip_asset 片段级）
        //     独立数据源，内部挂起主库事务执行；失败只记日志，不阻断切片删除
        //     （向量库不可用时残留孤儿 embedding 由重建/重入库覆盖）
        try {
            vectorAssetCleanupService.deleteBySliceId(sliceId);
        } catch (Exception e) {
            log.warn("Failed to clean vector assets for sliceId={}", sliceId, e);
        }

        // 5. Kafka 事件日志（后续替换为 KafkaTemplate 真实发送）
        log.info("Event: dims.slice.deleted.v1, sliceId={}, reason=manual, occurred_at={}",
                sliceId, LocalDateTime.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    @Override
    public DataSliceService.OpenScenarioDownload prepareOpenScenarioDownload(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        storageAccess.assertNotArchived(slice);

        SceneExtractionTask task = sceneExtractionTaskMapper.selectOne(
                new LambdaQueryWrapper<SceneExtractionTask>()
                        .eq(SceneExtractionTask::getSliceId, sliceId)
                        .orderByDesc(SceneExtractionTask::getCreatedAt)
                        .last("LIMIT 1"));
        if (task == null || !"SUCCESS".equals(task.getStatus())) {
            throw new RuntimeException("SCENE_NOT_READY: " + sliceId);
        }

        String prefix = StorageKeys.sceneDataPrefix(sliceId) + "openscenario/";
        String xoscKey = prefix + sliceId + ".xosc";
        String xodrKey = prefix + sliceId + ".xodr";
        if (!storageAccess.exists(xoscKey) || !storageAccess.exists(xodrKey)) {
            throw new RuntimeException("SCENE_FILE_NOT_FOUND: " + sliceId);
        }
        return new DataSliceService.OpenScenarioDownload(
                safeDownloadName(slice.getName(), sliceId) + "_openscenario.zip", xoscKey, xodrKey);
    }

    @Override
    public void writeOpenScenarioZip(DataSliceService.OpenScenarioDownload download, OutputStream output)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            writeZipEntry(zip, download.xoscKey(), download.xoscKey().substring(download.xoscKey().lastIndexOf('/') + 1));
            writeZipEntry(zip, download.xodrKey(), download.xodrKey().substring(download.xodrKey().lastIndexOf('/') + 1));
        }
    }

    @Override
    public DataSliceService.CarlaDownload prepareCarlaDownload(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        storageAccess.assertNotArchived(slice);
        SceneExtractionTask task = sceneExtractionTaskMapper.selectOne(
                new LambdaQueryWrapper<SceneExtractionTask>()
                        .eq(SceneExtractionTask::getSliceId, sliceId)
                        .orderByDesc(SceneExtractionTask::getCreatedAt)
                        .last("LIMIT 1"));
        if (task == null || !"SUCCESS".equals(task.getStatus())) {
            throw new RuntimeException("SCENE_NOT_READY: " + sliceId);
        }
        String prefix = StorageKeys.sceneDataPrefix(sliceId) + "carla/";
        // export_carla.py 的 direct-reconstruction 分支当前产出 materialized.*；
        // 兼容旧版按 sliceId 命名的产物，避免仅因文件名不同而误报不存在。
        List<String> keys = List.of(
                resolveCarlaFile(prefix, sliceId + ".xosc", "materialized.xosc"),
                resolveCarlaFile(prefix, sliceId + ".xodr", "materialized.xodr"),
                resolveCarlaFile(prefix, sliceId + ".json", "materialized.json"),
                prefix + "manifest.json");
        if (keys.stream().anyMatch(key -> !storageAccess.exists(key))) {
            throw new RuntimeException("SCENE_FILE_NOT_FOUND: " + sliceId);
        }
        return new DataSliceService.CarlaDownload(
                safeDownloadName(slice.getName(), sliceId) + "_carla.zip", keys);
    }

    private String resolveCarlaFile(String prefix, String preferredName, String fallbackName) {
        String preferred = prefix + preferredName;
        if (storageAccess.exists(preferred)) {
            return preferred;
        }
        return prefix + fallbackName;
    }

    @Override
    public void writeCarlaZip(DataSliceService.CarlaDownload download, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String key : download.keys()) {
                writeZipEntry(zip, key, key.substring(key.lastIndexOf('/') + 1));
            }
        }
    }

    private void writeZipEntry(ZipOutputStream zip, String key, String entryName) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream input = storageAccess.open(key)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    /**
     * 只校验可下载性，不碰数据内容。
     *
     * <p>⚠️ 票据接口必须走这里。它的职责只是发一张短时效票据，不需要读数据；
     * 早先它直接调 {@link #downloadRosbag(String)} 再 {@code try (InputStream ignored = ...)}，
     * 结果 HttpClient 的实体流在 close() 时把未读内容读干（连接复用所需）→
     * 356MB 的 bag 让取票阻塞 89s（实测），前端 axios 60s 超时 → 用户看到「点击下载没反应」。
     */
    @Override
    public void assertRosbagDownloadable(String sliceId) {
        requireDownloadableBag(sliceId);
    }

    @Override
    public DataSliceService.DownloadContent downloadRosbag(String sliceId) {
        BagRef ref = requireDownloadableBag(sliceId);
        StorageStat stat = storageAccess.stat(ref.key());
        return new DataSliceService.DownloadContent(
                storageAccess.open(ref.key()), stat.size(),
                safeDownloadName(ref.slice().getName(), sliceId) + extensionOf(ref.key()));
    }

    @Override
    public String resolveRosbagFileName(String sliceId) {
        BagRef ref = requireDownloadableBag(sliceId);
        return safeDownloadName(ref.slice().getName(), sliceId) + extensionOf(ref.key());
    }

    /**
     * 按存储 key 后缀决定下载文件的扩展名：切片产物已不只有 bag，
     * 恒润（Hirain）切片为转换产物打包的 {@code .zip}，CFDI 为 {@code .tar.gz}。
     */
    private static String extensionOf(String key) {
        String lower = (key == null) ? "" : key.toLowerCase();
        if (lower.endsWith(".tar.gz")) {
            return ".tar.gz";
        }
        int dot = lower.lastIndexOf('.');
        int slash = lower.lastIndexOf('/');
        if (dot > slash) {
            return lower.substring(dot);
        }
        return ".bag";
    }

    /**
     * 校验并解析 bag 的存储引用（存在 → 未归档 → 对象存在），只做元数据查询。
     */
    private BagRef requireDownloadableBag(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        storageAccess.assertNotArchived(slice);
        String key = storageAccess.resolveBagKey(slice);
        if (!storageAccess.exists(key)) {
            throw new RuntimeException("BAG_FILE_NOT_FOUND: " + sliceId);
        }
        return new BagRef(slice, key);
    }

    private record BagRef(DataSlice slice, String key) {
    }

    private static String safeDownloadName(String name, String fallback) {
        String value = (name == null || name.isBlank()) ? fallback : name.trim();
        return value.replaceAll("[\\\\/:*?\"<>|\r\n]+", "_");
    }

    // ========================================================================
    // 日志下载（读取 data_slice.import_source_dir 指向的会话目录）
    // ========================================================================

    /**
     * 解析并校验日志来源，收集待下载的日志文件（全程只 stat，不读内容）。
     *
     * <p>与 {@link #downloadRosbag(String)} 的差别：bag 走对象存储，日志走会话目录 ——
     * 导入流程把整个会话目录落在 NFS 上，路径记在 {@code import_source_dir}。
     * 该路径是服务器视角的 POSIX 路径，进程未必看得见，所以这里要通过
     * {@link LogFileStore} 抽象在「本地文件系统」与「NFS 直连」之间选一条能用的通道，
     * 且不碰 {@link StorageAccess}、不做归档判定（归档只约束对象存储里的 bag）。
     */
    @Override
    public DataSliceService.LogDownload prepareLogDownload(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        LogPath logical = parseLogicalPath(slice);
        DataSliceService.LogSource source = locateLogSource(slice, logical);
        List<DataSliceService.LogDownload.LogFile> files = collectLogFiles(source);
        if (files.isEmpty()) {
            throw new RuntimeException("LOG_NOT_FOUND: no log file under " + source.dir()
                    + " (" + source.label() + ", entries: " + previewEntries(source) + ")");
        }
        long totalSize = files.stream().mapToLong(DataSliceService.LogDownload.LogFile::size).sum();
        if (totalSize > logMaxTotalBytes) {
            throw new RuntimeException("LOG_TOO_LARGE: " + totalSize + " bytes exceeds limit "
                    + logMaxTotalBytes + " bytes (" + files.size() + " files) under " + source.dir());
        }
        boolean zip = files.size() > 1;
        String fileName = zip
                ? safeDownloadName(slice.getName(), sliceId) + "_logs.zip"
                : LogFileStore.fileName(files.get(0).path());
        log.info("切片 {} 日志下载：来源 {}，目录 {}，{} 个文件 / {} 字节，zip={}",
                sliceId, source.label(), source.dir(), files.size(), totalSize, zip);
        return new DataSliceService.LogDownload(fileName, zip, files, totalSize, source);
    }

    @Override
    public void writeLogBundle(DataSliceService.LogDownload download, OutputStream output) throws IOException {
        LogFileStore store = storeOf(download.source().type());
        if (!download.zip()) {
            try (InputStream input = store.open(download.files().get(0).path())) {
                input.transferTo(output);
                output.flush();
            }
            return;
        }
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (DataSliceService.LogDownload.LogFile file : download.files()) {
                zip.putNextEntry(new ZipEntry(file.entryName()));
                try (InputStream input = store.open(file.path())) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * 解析并定位日志来源：白名单校验 → 按 {@code log-scope} 确定扫描根（会话目录 / md5 目录）
     * → 候选形态命中（带/不带 {@code yyyyMMdd} 日期段）→ 按 {@code log-source} 选择本地/NFS 通道。
     *
     * <p>三类失败各有独立错误码，前端据此给出不同文案：
     * {@code LOG_PATH_MISSING}（库表里就没记路径）/ {@code LOG_PATH_NOT_ALLOWED}（不在允许根目录内）
     * / {@code LOG_PATH_NOT_FOUND}（候选路径都不存在、不是目录或不可读）。
     *
     * <p><b>为什么要试多个候选</b>：库表记的路径与磁盘实际存放存在两种形态 —— 带/不带
     * {@code {yyyyMMdd}} 日期段（现网两类同时存在）。只认原路径会把「库表记错」误报成
     * 「路径不对」，而这类误报在用户侧无从分辨。命中非原始形态时打 WARN，便于回头修数据源。
     *
     * <p><b>{@code md5-dir} 范围</b>：同一 md5 下现网确实存在多个会话目录
     * （实测 {@code 096b6130.../} 下有 {@code ..._9984/_9985/_9986_public} 三个），切片却按会话
     * 逐条入库。把扫描根从会话目录上提一级到 md5 目录后，{@link #collectLogFiles(LogSource)}
     * 会递归扫到其中<b>所有</b>会话目录下的日志。
     */
    private DataSliceService.LogSource locateLogSource(DataSlice slice, LogPath logical) {
        if (!isUnderAllowedRoot(logical)) {
            throw new RuntimeException("LOG_PATH_NOT_ALLOWED: " + logical.posix()
                    + " is outside allowed roots [" + logAllowedRoots + "]");
        }
        List<String> tried = new ArrayList<>();
        boolean wantLocal = "local".equalsIgnoreCase(logSourceMode) || "auto".equalsIgnoreCase(logSourceMode);
        boolean wantNfs = "nfs".equalsIgnoreCase(logSourceMode) || "auto".equalsIgnoreCase(logSourceMode);

        for (LogPath root : scopeRoots(logical)) {
            for (LogPath candidate : logDirShapes(slice, root)) {
                if (wantLocal) {
                    tried.add("local:" + candidate.posix());
                    Path io = toLocalIoPath(candidate);
                    if (localStore.isDirectory(io.toString())) {
                        if (!candidate.equals(logical)) {
                            log.warn("[日志下载] import_source_dir 与磁盘不一致，已按候选形态命中：库表={} 实际={}",
                                    logical.posix(), candidate.posix());
                        }
                        return new DataSliceService.LogSource("local", localStore.label(), io.toString());
                    }
                }
                if (wantNfs && nfsStore != null && nfsStore.configured()) {
                    String nfsPath = candidate.stripPrefix(nfsStripPrefix);
                    if (nfsPath == null) {
                        tried.add("nfs:(skip " + candidate.posix() + " not under " + nfsStripPrefix + ")");
                        continue;
                    }
                    tried.add("nfs:" + candidate.posix());
                    if (nfsStore.isDirectory(nfsPath)) {
                        return new DataSliceService.LogSource("nfs", nfsStore.label(), nfsPath);
                    }
                }
            }
        }
        // 报错带上「试过哪些形态」与最近存在祖先的内容 —— 日期段记错时，看目录一眼就能确认
        throw new RuntimeException("LOG_PATH_NOT_FOUND: " + logical.posix()
                + " not found (os=" + System.getProperty("os.name") + ", mode=" + logSourceMode
                + ", allowed roots [" + logAllowedRoots + "], tried=" + tried
                + ", parent entries: "
                + previewNearestExisting(toLocalIoPath(logical).getParent()) + ")");
    }

    /**
     * 把 {@link LogPath} 渲染成本地文件系统可解析的 {@link Path}。
     *
     * <p>库表记的是服务器视角 POSIX 路径（{@code /data/downloads/...}）；但 Windows 开发机上
     * 若直接把本地绝对路径（{@code C:\...}）喂给 {@code LogPath}，段列表会带盘符段，
     * {@code posix()} 渲染成 {@code /C:/...} —— 那不是合法本地路径。这里做一次「还原」：
     * 形如 {@code /<盘符>:/...} 时去掉前导 {@code /}；其余按 POSIX 原样交给
     * {@code Path.of(String)}（Windows 会把 {@code /data} 归到当前盘根，再由
     * {@code path-mappings} 映射到真实位置），最后统一走路径映射。
     */
    private Path toLocalIoPath(LogPath logical) {
        String posix = logical.posix();
        if (DRIVE_LETTER_PATH.matcher(posix).matches()) {
            posix = posix.substring(1);
        }
        return applyLogPathMapping(Path.of(posix).toAbsolutePath().normalize());
    }

    /**
     * 解析库表 {@code import_source_dir} 为路径值对象；为空或含非法段（{@code .}/{@code ..}）时报
     * {@code LOG_PATH_MISSING}，与「目录不存在」区分开。
     */
    private LogPath parseLogicalPath(DataSlice slice) {
        String raw = slice.getImportSourceDir();
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("LOG_PATH_MISSING: data_slice.import_source_dir is empty, slice_id="
                    + slice.getId());
        }
        try {
            return LogPath.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("LOG_PATH_MISSING: invalid import_source_dir " + raw
                    + " (" + e.getMessage() + "), slice_id=" + slice.getId());
        }
    }

    /**
     * 按 {@code log-scope} 决定扫描根：{@code session}（默认）= import_source_dir 指向的会话目录；
     * {@code md5-dir} = 上提一级到 md5 目录（把同一 md5 下<b>所有</b>会话目录的日志一起收）。
     */
    private List<LogPath> scopeRoots(LogPath logical) {
        if ("md5-dir".equalsIgnoreCase(logScope)) {
            LogPath parent = logical.parent();
            return parent == null ? List.of(logical) : List.of(parent);
        }
        return List.of(logical);
    }

    /**
     * 候选形态（顺序即优先级）：原路径 → 去掉日期段 → 补上日期段（日期从 {@code session_id} 推）。
     *
     * <p>只做这三种，不做模糊搜索：匹配不到就如实报 {@code LOG_PATH_NOT_FOUND}，
     * 不猜路径 —— 猜错会把「数据没同步下来」伪装成「日志就是这个」。
     */
    private static List<LogPath> logDirShapes(DataSlice slice, LogPath dir) {
        Set<LogPath> shapes = new LinkedHashSet<>();
        shapes.add(dir);
        shapes.add(dir.dropDateSegment());
        shapes.add(dir.insertDateSegment(LogPath.dateFromSessionId(slice.getSessionId()),
                slice.getImportMd5()));
        return List.copyOf(shapes);
    }

    /**
     * 按来源类型取读取通道：{@code nfs} 走 NFS 直连，其余（含默认 {@code local}）走本地文件系统。
     */
    private LogFileStore storeOf(String type) {
        return "nfs".equals(type) ? nfsStore : localStore;
    }

    /**
     * 逻辑路径 → 实际 IO 路径的前缀替换（{@code 源>目标}，逗号分隔）；未配置或未命中则原样返回。
     *
     * <p>只替换前缀，不做存在性校验 —— 校验交给调用方统一做，避免「映射命中但目录不存在」
     * 这种情况被误报成「路径不对」。
     */
    private Path applyLogPathMapping(Path dir) {
        if (logPathMappings == null || logPathMappings.isBlank()) {
            return dir;
        }
        for (String pair : logPathMappings.split(",")) {
            String[] kv = pair.split(">", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                log.warn("[日志下载] 路径映射配置无法解析，已跳过：{}", pair);
                continue;
            }
            Path from = Path.of(kv[0].trim()).toAbsolutePath().normalize();
            if (!dir.startsWith(from)) {
                continue;
            }
            Path to = Path.of(kv[1].trim()).toAbsolutePath().normalize();
            Path mapped = to.resolve(from.relativize(dir)).normalize();
            log.info("[日志下载] 路径映射生效：{} -> {}", dir, mapped);
            return mapped;
        }
        return dir;
    }

    /**
     * 空字符串表示显式关闭白名单（仅排障时用），否则必须命中任一允许根目录。
     */
    private boolean isUnderAllowedRoot(LogPath dir) {
        if (logAllowedRoots == null || logAllowedRoots.isBlank()) {
            return true;
        }
        for (String root : logAllowedRoots.split(",")) {
            String trimmed = root.trim();
            if (!trimmed.isEmpty() && dir.startsWith(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 基于 {@link LogFileStore} 递归收集日志文件（本地文件系统与 NFS 共用同一段逻辑），
     * 按相对路径排序（保证多次调用结果稳定）。
     *
     * <p>日志口径（并集，前三条可配置，见 {@code dims.download.log.dir-names} /
     * {@code dir-name-contains} / {@code meta-names}）：
     * <ol>
     *   <li>路径中任一目录段是 {@code log}/{@code logs} → 该目录下所有文件；</li>
     *   <li>目录段含 {@code forever_log} → 该目录下所有文件。
     *       <b>这条是现网唯一能命中的</b>：BOS 上真实会话目录为
     *       {@code public/<ts>_forever_log_<car>/<xxx>.log.<时间戳>.gz}，
     *       整个会话目录里<b>没有</b> {@code log/} 子目录 —— 只认 {@code log} 会一个文件都收不到；</li>
     *   <li>文件名形如 {@code *.log*}/{@code *.log_*} → 单个文件（日志落在非日志目录时兜底）；</li>
     *   <li>文件名在 {@code meta-names}（默认 {@code meta,log_info.json}）中、且与<b>某个命中日志口径的
     *       目录同级</b> → 一并收（{@code meta} 是这次采集的元信息，{@code log_info.json} 是日志清单；
     *       只有日志没有它们，拿到的包无法判断对应哪次采集）。</li>
     * </ol>
     *
     * <p>反向排除：{@link #NON_LOG_FILE_NAMES} 里的平台簿记文件一律不收；
     * {@code .mfbag}（图像/点云包，几十 MB）与 {@code help/*.json.gz} 因不匹配任何一条规则天然被排除。
     *
     * <p>不使用 {@code FOLLOW_LINKS}，避免目录内软链把扫描带出扫描根；
     * 深度与节点数有预算上限，防御异常目录树（软链环、超深目录）把请求拖死。
     */
    private List<DataSliceService.LogDownload.LogFile> collectLogFiles(DataSliceService.LogSource source) {
        LogFileStore store = storeOf(source.type());
        String root = source.dir();
        List<DataSliceService.LogDownload.LogFile> files = new ArrayList<>();
        int[] budget = {0};
        walkLogTree(store, root, "", 0, files, budget);
        files.sort(Comparator.comparing(DataSliceService.LogDownload.LogFile::entryName));
        return files;
    }

    /**
     * 深度优先遍历日志目录树：目录段命中日志口径（{@link #isLogDirName}）→ 整目录收；
     * 目录下存在命中日志口径的子目录 → 同级的 {@code meta-names} 文件一并收。
     */
    private void walkLogTree(LogFileStore store, String absDir, String relDir, int depth,
                             List<DataSliceService.LogDownload.LogFile> files, int[] budget) {
        if (depth > LOG_SCAN_MAX_DEPTH || budget[0]++ > LOG_SCAN_MAX_ENTRIES) {
            return;
        }
        List<String> children = store.list(absDir);
        if (children.isEmpty()) {
            return;
        }
        boolean dirIsLog = isLogDirName(lastSegment(relDir));
        boolean hasLogSubdir = children.stream()
                .filter(store::isDirectory)
                .anyMatch(child -> isLogDirName(LogFileStore.fileName(child)));
        for (String child : children) {
            String name = LogFileStore.fileName(child);
            if (store.isDirectory(child)) {
                String childRel = relDir.isEmpty() ? name : relDir + "/" + name;
                walkLogTree(store, child, childRel, depth + 1, files, budget);
                continue;
            }
            if (NON_LOG_FILE_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String rel = relDir.isEmpty() ? name : relDir + "/" + name;
            if (dirIsLog || (hasLogSubdir && isMetaName(name)) || LOG_FILE_NAME.matcher(name).matches()) {
                files.add(new DataSliceService.LogDownload.LogFile(child, rel, store.size(child)));
            }
        }
    }

    /**
     * 文件名是否在 {@code meta-names} 配置中（与日志目录同级的元数据，随日志一起下）。
     */
    private boolean isMetaName(String name) {
        return splitCsv(logMetaNames).contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 相对路径最后一段（目录名）；空路径返回空串。
     */
    private static String lastSegment(String relDir) {
        if (relDir == null || relDir.isEmpty()) {
            return "";
        }
        int idx = relDir.lastIndexOf('/');
        return idx < 0 ? relDir : relDir.substring(idx + 1);
    }

    /**
     * 目录名等于 {@code dir-names} 之一、或包含 {@code dir-name-contains} 之一 → 该目录整目录算日志。
     */
    private boolean isLogDirName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String candidate : splitCsv(logDirNames)) {
            if (candidate.equals(lower)) {
                return true;
            }
        }
        for (String candidate : splitCsv(logDirNameContains)) {
            if (lower.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(part -> part.trim().toLowerCase(Locale.ROOT))
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /**
     * 目录内容摘要：报「目录里没有日志」时把实际内容带上，否则用户只能看到一个路径无从判断
     * 到底是没有日志、还是日志没同步下来、还是口径不对。该重载走 {@link LogFileStore}
     * （兼容 NFS 直连来源，不依赖服务器本地文件系统）。
     */
    private String previewEntries(DataSliceService.LogSource source) {
        if (source == null) {
            return "(no source)";
        }
        LogFileStore store = storeOf(source.type());
        List<String> children = store.list(source.dir());
        if (children.isEmpty()) {
            return "(empty directory)";
        }
        return children.stream()
                .limit(LOG_DIR_PREVIEW_LIMIT)
                .map(c -> c + (store.isDirectory(c) ? "/" : ""))
                .sorted()
                .collect(Collectors.joining(", "));
    }

    /**
     * Path 版目录摘要（本地文件系统），供 {@link #previewNearestExisting} 使用。
     */
    private static String previewEntries(Path dir) {
        if (dir == null) {
            return "(no parent)";
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> names = stream.limit(LOG_DIR_PREVIEW_LIMIT)
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted()
                    .collect(Collectors.toList());
            return names.isEmpty() ? "(empty directory)" : String.join(", ", names);
        } catch (IOException e) {
            return "unreadable (" + e.getMessage() + ")";
        }
    }

    /**
     * 报「路径不对」时给出<b>最近存在</b>的祖先及其内容，并带上该祖先路径。
     *
     * <p>直接列父目录在路径整体缺失时只会得到「unreadable」—— 而「父目录不存在」恰恰是
     * 日期段记错/多写了一层目录的典型症状。往上找到第一个真实存在的目录，用户扫一眼
     * 就知道磁盘上是 {@code <md5>/} 还是 {@code <yyyyMMdd>/<md5>/}。
     */
    private static String previewNearestExisting(Path dir) {
        Path cursor = dir;
        while (cursor != null && !Files.isDirectory(cursor)) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return "(no existing ancestor)";
        }
        return cursor + " -> [" + previewEntries(cursor) + "]";
    }

    // ========================================================================
    // 私有辅助方法
    // ========================================================================

    /**
     * C-5: 排序参数白名单校验
     * <p>
     * 防止 SQL 注入：仅允许白名单中的列名和 ASC/DESC 方向。
     * 格式 "field:direction"，如 "created_at:desc"。
     * 默认返回 {@code created_at DESC}。
     * <p>
     * 特殊值 {@code preset:asc}：按请求传入 slice_ids 的先后顺序返回，
     * 用于保留语义检索（以文搜图 / 以图搜图）Reranker 打分的排序结果——
     * 前端拿到的是「按得分降序、并按 slice_id 去重」的 ID 列表，
     * 若此处再按 created_at 排序，相关度排序就丢了。
     * {@code preset:desc} 则按传入顺序倒序（即得分升序）。
     */
    private String validateSort(String sort) {
        if (sort == null || sort.isEmpty()) return "created_at DESC";
        // 去掉默认的 "created_at:desc" 中的 "created_at:desc" 映射
        String[] parts = sort.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid sort format, expected 'field:direction'");
        }
        if (!ALLOWED_SORT_COLUMNS.contains(parts[0])
                || !ALLOWED_SORT_DIRECTIONS.contains(parts[1].toUpperCase())) {
            throw new IllegalArgumentException(
                    "Invalid sort column or direction. Allowed columns: " + ALLOWED_SORT_COLUMNS
                            + ", allowed directions: " + ALLOWED_SORT_DIRECTIONS);
        }
        // preset 不是真实列名，映射为 XML 中 CASE 排序分支使用的哨兵值
        if ("preset".equals(parts[0])) {
            return "PRESET " + parts[1].toUpperCase();
        }
        return parts[0] + " " + parts[1].toUpperCase();
    }

    /**
     * 按需加载状态推导（列表 / 详情共用）。
     * META_READY → NOT_LOADED（仅 meta + 前视鱼眼，未加载全量）；
     * LOADING → LOADING（全量拉取进行中）；
     * ERROR 且记录了 import_bos_key → NOT_LOADED（上次加载失败，可再次点「加载」重试）；
     * 其余状态 → LOADED（全量数据已加载完成，可回放 / 下载）。
     */
    private static String resolveLoadState(DataSlice entity) {
        String status = entity.getStatus();
        if ("LOADING".equals(status)) {
            return "LOADING";
        }
        if ("META_READY".equals(status)) {
            return "NOT_LOADED";
        }
        if ("ERROR".equals(status)
                && entity.getImportBosKey() != null && !entity.getImportBosKey().isBlank()) {
            return "NOT_LOADED";
        }
        return "LOADED";
    }


    /**
     * 切片名第 2 段是否「像车型代码」：字符集为字母/数字/连字符/点，且至少含 1 个字母。
     *
     * <p>用于排除纯数字片段（时间戳、md5 前缀等）被误当车型显示。</p>
     */
    private static boolean isModelCodeLike(String seg) {
        if (seg == null || seg.isBlank()) {
            return false;
        }
        if (!MODEL_CODE_PATTERN.matcher(seg).matches()) {
            return false;
        }
        return seg.chars().anyMatch(Character::isLetter);
    }

    /**
     * 车型兜底：名称解析不出车型的列表项，按 VIN 批量反查 {@code vin_md5_mapping.model_code}。
     *
     * <p>Hirain（恒润）源切片只有转换后的 mcap/zip，不走 rosbag 统计流水线，
     * {@code slice_stats} 无记录；且其切片名是 {@code {VIN尾号}_{时间戳}_public}，
     * 名称里也没有车型。只能按 VIN 从车辆映射表取车型。一次 IN 查询，避免 N+1。</p>
     */
    private void fillVehicleTypeByVin(List<SliceListResponse.SliceItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> vins = items.stream()
                .filter(it -> it.getVehicleType() == null || it.getVehicleType().isEmpty())
                .map(SliceListResponse.SliceItem::getVin)
                .filter(vin -> vin != null && !vin.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (vins.isEmpty()) {
            return;
        }
        Map<String, String> modelByVin;
        try {
            modelByVin = vinMd5MappingMapper.selectList(
                            new LambdaQueryWrapper<VinMd5Mapping>().in(VinMd5Mapping::getVin, vins))
                    .stream()
                    .filter(m -> m.getVin() != null && m.getModelCode() != null && !m.getModelCode().isBlank())
                    .collect(Collectors.toMap(VinMd5Mapping::getVin, VinMd5Mapping::getModelCode, (a, b) -> a));
        } catch (Exception e) {
            log.warn("[SLICE] 按 VIN 反查车型失败，车型留空: {}", e.getMessage());
            return;
        }
        if (modelByVin.isEmpty()) {
            return;
        }
        for (SliceListResponse.SliceItem item : items) {
            if (item.getVehicleType() != null && !item.getVehicleType().isEmpty()) {
                continue;
            }
            String model = modelByVin.get(item.getVin());
            if (model != null) {
                item.setVehicleType(model);
            }
        }
    }

    /**
     * Entity → SliceItem 转换（列表项）
     */
    private SliceListResponse.SliceItem toSliceItem(DataSlice entity) {
        SliceListResponse.SliceItem item = new SliceListResponse.SliceItem();
        item.setId(entity.getId());
        item.setName(entity.getName());
        item.setFileSize(entity.getFileSize());
        item.setDurationMs(entity.getDurationMs());
        item.setStartTs(formatTime(entity.getStartTs()));
        item.setEndTs(formatTime(entity.getEndTs()));
        item.setTriggerTime(formatTime(entity.getTriggerTime()));
        item.setGpsBbox(toGpsBbox(entity));
        item.setSensorTypes(parseSensorTypes(entity.getSensorTypes()));
        item.setMsgCountTotal(entity.getMsgCountTotal());
        item.setStatus(entity.getStatus());
        item.setLoadState(resolveLoadState(entity));
        item.setErrorMsg(entity.getErrorMsg());
        item.setSourceType(entity.getSourceType());
        item.setEventName(entity.getEventName());
        item.setDownloadUrl("/v1/data-slices/" + entity.getId() + "/exports");
        item.setCreatedAt(formatTime(entity.getCreatedAt()));

        // 车辆型号（slice_stats LEFT JOIN 冗余列）
        String vehicleType = entity.getVehicleType();
        if (vehicleType != null && !vehicleType.isEmpty()) {
            item.setVehicleType(vehicleType);
        } else if ("MMT".equals(entity.getSourceType())) {
            // 车辆型号兜底：从切片名第 2 段提取（形如 JMC_CX835ICA_..._<md5>_<ts>）。
            // ⚠️ 仅当该段「像车型代码」时才采用（含字母、非纯数字），避免把时间戳/md5 段当车型。
            // 名称为空 / 不含下划线（VIN 直名、导入报错串等）时不抛错，vehicle_type 留空。
            String name = entity.getName();
            if (name != null) {
                String[] parts = name.split("_");
                if (parts.length >= 2 && isModelCodeLike(parts[1])) {
                    item.setVehicleType(parts[1]);
                }
            }
        } else if ("Hirain".equals(entity.getSourceType())) {
            item.setVehicleType("CX835base");
        }
        // VIN + UTC 时钟偏移（data_slice 原生列）
        item.setVin(entity.getVin());
        item.setUtcDiffUs(entity.getUtcDiffUs());
        // 功能激活状态（slice_stats LEFT JOIN 冗余列）
        item.setNoaActive(entity.getNoaActive());
        item.setAebActive(entity.getAebActive());
        item.setApaActive(entity.getApaActive());
        item.setAccActive(entity.getAccActive());
        item.setLccActive(entity.getLccActive());
        item.setRoamingActive(entity.getRoamingActive());
        item.setAvpActive(entity.getAvpActive());
        item.setFcwActive(entity.getFcwActive());
        item.setAesActive(entity.getAesActive());
        item.setEssActive(entity.getEssActive());
        // 异常标记：data_slice.status='ERROR'、slice_stats 无统计记录，或统计提取 FAILED。
        // （statsFilter 只统计 SUCCESS 记录，status='FAILED' 的切片同样不贡献图表数据，一并标红）
        item.setHasAlerts("ERROR".equals(entity.getStatus())
                || entity.getStatsSliceId() == null
                || "FAILED".equals(entity.getStatsStatus()));

        // 查询标签引用（名称由 SIMS 词表缓存解析；失效引用回落「未知标签(...)」占位）
        item.setTags(resolveTagRefs(dataSliceTagMapper.selectBySliceId(entity.getId())));

        return item;
    }

    /**
     * 打标关联行 → 标签名列表（详情页用）。
     *
     * <p>本地 {@code tag} 表已随 V29 移除；名称从 {@link SimsTagCatalog} 的进程内词表
     * 解析（一次加载、TTL 内复用，不会因切片条数产生 N+1 次外部调用）。
     * 解析不到（标签已在 SIMS 侧删除 / V20 遗留的场景域标签）返回「未知标签(...)」占位。
     */
    private List<String> resolveTagNames(List<DataSliceTag> rows) {
        return rows.stream().map(row -> simsTagCatalog.nameOf(row.getTagId())).collect(Collectors.toList());
    }

    /**
     * 打标关联行 → 标签引用列表（列表页用）。
     *
     * <p>刻意**不过滤**解析失败的引用：关联行是「当时确实打过这个标」的事实，
     * 数量应与实际打标一致；名称侧给占位即可，静默丢掉会让用户以为标签凭空少了。
     */
    private List<TagRef> resolveTagRefs(List<DataSliceTag> rows) {
        return rows.stream().map(row -> {
            TagRef ref = simsTagCatalog.find(row.getTagId());
            return ref != null ? ref : new TagRef(row.getTagId(), simsTagCatalog.nameOf(row.getTagId()), null);
        }).collect(Collectors.toList());
    }

    /**
     * Entity + Streams + Tags + Stats → SliceDetailResponse 转换（详情页）
     */
    private SliceDetailResponse toSliceDetailResponse(DataSlice entity,
                                                      List<SensorStream> streams,
                                                      List<String> tagNames,
                                                      SliceStats stats) {
        SliceDetailResponse resp = new SliceDetailResponse();
        resp.setId(entity.getId());
        resp.setName(entity.getName());
        resp.setImportSourceDir(entity.getImportSourceDir());
        resp.setFileSize(entity.getFileSize());
        resp.setDurationMs(entity.getDurationMs());
        resp.setStartTs(formatTime(entity.getStartTs()));
        resp.setEndTs(formatTime(entity.getEndTs()));
        resp.setGpsBbox(toGpsBbox(entity));
        resp.setSensorTypes(parseSensorTypes(entity.getSensorTypes()));
        resp.setMsgCountTotal(entity.getMsgCountTotal());
        resp.setTags(tagNames);
        resp.setStatus(entity.getStatus());
        resp.setLoadState(resolveLoadState(entity));
        resp.setErrorMsg(entity.getErrorMsg());
        resp.setSourceType(entity.getSourceType());
        resp.setDownloadUrl("/v1/data-slices/" + entity.getId() + "/exports");
        resp.setCreatedAt(formatTime(entity.getCreatedAt()));

        // 查询关联的导入任务 ID（用于前端概览 Tab 关联任务状态时间线）
        IngestionTask task = ingestionTaskMapper.selectOne(
                new LambdaQueryWrapper<IngestionTask>()
                        .eq(IngestionTask::getSliceId, entity.getId())
                        .last("LIMIT 1")
        );
        if (task != null) {
            resp.setTaskId(task.getId());
        }

        // data_slice 原生列
        resp.setVin(entity.getVin());
        resp.setSystemName(entity.getSystemName());

        // slice_stats 1:1 字段（无统计记录时全部保持 null）
        if (stats != null) {
            resp.setVehicleType(stats.getVehicleType());
            resp.setVehicleMileageDelta(stats.getVehicleMileageDelta());
            resp.setSpeedAvg(stats.getSpeedAvg());
            resp.setSpeedMax(stats.getSpeedMax());
            resp.setRoadScene(stats.getRoadScene());
            resp.setHighwayMode(stats.getHighwayMode());
            resp.setCurvatureAvg(stats.getCurvatureAvg());
            resp.setGnssSatNumAvg(stats.getGnssSatNumAvg());
            resp.setGnssHdopAvg(stats.getGnssHdopAvg());
            resp.setLaneChangeCountLeft(stats.getLaneChangeCountLeft());
            resp.setLaneChangeCountRight(stats.getLaneChangeCountRight());
            resp.setNoaActive(stats.getNoaActive());
            resp.setAebActive(stats.getAebActive());
            resp.setApaActive(stats.getApaActive());
            resp.setAccActive(stats.getAccActive());
            resp.setLccActive(stats.getLccActive());
            resp.setRoamingActive(stats.getRoamingActive());
            resp.setAvpActive(stats.getAvpActive());
            resp.setFcwActive(stats.getFcwActive());
            resp.setAesActive(stats.getAesActive());
            resp.setEssActive(stats.getEssActive());
        }
        // Hirain 835 车型兜底（统计任务尚未跑完或无 stats 记录时仍展示车型）
        if ((resp.getVehicleType() == null || resp.getVehicleType().isBlank())
                && "Hirain".equals(resp.getSourceType())) {
            resp.setVehicleType("CX835base");
        }

        // 转换 SensorStream → StreamInfo
        List<SliceDetailResponse.StreamInfo> streamInfos = streams.stream()
                .map(this::toStreamInfo)
                .collect(Collectors.toList());
        resp.setStreams(streamInfos);

        // 仅 CFDI 链路切片（cfdi_meta 有记录；CFDI 上传与 LOCAL 目录导入均写 cfdi_meta）加载标定和元数据
        SliceDetailResponse.CfdiMetaItem cfdiMeta = loadCfdiMeta(entity.getId());
        if (cfdiMeta != null) {
            resp.setCameraCalibrations(loadCameraCalibrations(entity.getId()));
            resp.setCfdiMeta(cfdiMeta);
        }

        return resp;
    }

    /**
     * 加载摄像头标定参数列表
     */
    private List<SliceDetailResponse.CameraCalibrationItem> loadCameraCalibrations(String sliceId) {
        List<CameraCalibration> calibs = cameraCalibrationMapper.selectList(
                new LambdaQueryWrapper<CameraCalibration>()
                        .eq(CameraCalibration::getSliceId, sliceId)
        );
        return calibs.stream().map(this::toCalibrationItem).collect(Collectors.toList());
    }

    /**
     * CameraCalibration → CameraCalibrationItem 转换
     */
    private SliceDetailResponse.CameraCalibrationItem toCalibrationItem(CameraCalibration calib) {
        SliceDetailResponse.CameraCalibrationItem item = new SliceDetailResponse.CameraCalibrationItem();
        item.setId(calib.getId());
        item.setSensorName(calib.getSensorName());
        item.setSerialNumber(calib.getSerialNumber());
        item.setCameraModel(calib.getCameraModel());
        item.setFx(calib.getFx());
        item.setFy(calib.getFy());
        item.setCx(calib.getCx());
        item.setCy(calib.getCy());
        item.setKc2(calib.getKc2());
        item.setKc3(calib.getKc3());
        item.setKc4(calib.getKc4());
        item.setKc5(calib.getKc5());
        item.setRS2b(parseDoubleArray(calib.getRS2b()));
        item.setTS2b(parseDoubleArray(calib.getTS2b()));
        Integer fisheye = calib.getIsFisheye();
        item.setIsFisheye(fisheye != null ? fisheye == 1 : null);
        item.setWidth(calib.getWidth());
        item.setHeight(calib.getHeight());
        item.setCalibVersion(calib.getCalibVersion());
        item.setCalibDate(calib.getCalibDate());
        return item;
    }

    /**
     * 加载 CFDI 元数据摘要
     */
    private SliceDetailResponse.CfdiMetaItem loadCfdiMeta(String sliceId) {
        CfdiMeta meta = cfdiMetaMapper.selectOne(
                new LambdaQueryWrapper<CfdiMeta>().eq(CfdiMeta::getSliceId, sliceId)
        );
        if (meta == null) return null;

        SliceDetailResponse.CfdiMetaItem item = new SliceDetailResponse.CfdiMetaItem();
        item.setCarType(meta.getCarType());
        item.setEventName(meta.getEventName());
        item.setProject(meta.getProject());
        item.setCollectType(meta.getCollectType());
        item.setDataSourceEnv(meta.getDataSourceEnv());
        item.setVin(meta.getVin());
        item.setGoodFlag(meta.getGoodFlag());
        item.setDumpFinish(meta.getDumpFinish());
        // JSON 字段: 直接解析字符串为 Object (Jackson 自动转为 Map/List)
        item.setMetaData(parseJsonField(meta.getMetaData()));
        item.setFilterMetaData(parseJsonField(meta.getFilterMetaData()));
        item.setLogInfoData(parseJsonField(meta.getLogInfoData()));
        item.setUploadDoneData(parseJsonField(meta.getUploadDoneData()));
        // 解析 sub_span_us 和 trigger_timestamp_us 从 meta_data JSON
        if (meta.getMetaData() != null) {
            try {
                JsonNode metaData = objectMapper.readTree(meta.getMetaData());
                if (metaData.has("sub_span_us") && !metaData.get("sub_span_us").isNull()) {
                    Map<String, Long> subSpanUs = new HashMap<>();
                    metaData.get("sub_span_us").fields().forEachRemaining(entry ->
                            subSpanUs.put(entry.getKey(), entry.getValue().asLong())
                    );
                    item.setSubSpanUs(subSpanUs);
                }
                if (metaData.has("trigger_timestamp_us")) {
                    item.setTriggerTimestampUs(metaData.get("trigger_timestamp_us").asText());
                }
            } catch (Exception e) {
                log.warn("Failed to parse meta_data JSON for sliceId={}", sliceId, e);
            }
        }
        return item;
    }

    /**
     * 将 JSON 字符串解析为 Object（Jackson 自动转换为 Map / List / String / Number）
     */
    private Object parseJsonField(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return json; // fallback: 以字符串返回
        }
    }

    /**
     * JSON 数组字符串 → List&lt;Double&gt; 解析
     */
    private List<Double> parseDoubleArray(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse double array JSON: {}", json, e);
            return null;
        }
    }

    /**
     * SensorStream → StreamInfo 转换
     */
    private SliceDetailResponse.StreamInfo toStreamInfo(SensorStream stream) {
        SliceDetailResponse.StreamInfo info = new SliceDetailResponse.StreamInfo();
        info.setId(stream.getId());
        info.setTopic(stream.getTopic());
        info.setMsgType(stream.getMsgType());
        info.setSensorCategory(stream.getSensorCategory());
        info.setMsgCount(stream.getMsgCount());
        info.setFrequencyHz(stream.getFrequencyHz());
        info.setFirstTs(formatTime(stream.getFirstTs()));
        info.setLastTs(formatTime(stream.getLastTs()));
        info.setSensorName(stream.getSensorName());
        info.setDurationUs(stream.getDurationUs());
        info.setMetadata(stream.getMetadata());
        return info;
    }

    /**
     * GPS 字段 → GpsBbox 对象组装
     */
    private GpsBbox toGpsBbox(DataSlice entity) {
        if (entity.getGpsMinLat() == null && entity.getGpsMinLon() == null
                && entity.getGpsMaxLat() == null && entity.getGpsMaxLon() == null) {
            return null;
        }
        GpsBbox bbox = new GpsBbox();
        bbox.setMinLat(entity.getGpsMinLat());
        bbox.setMinLon(entity.getGpsMinLon());
        bbox.setMaxLat(entity.getGpsMaxLat());
        bbox.setMaxLon(entity.getGpsMaxLon());
        return bbox;
    }

    /**
     * sensor_types JSON 字符串 → List 反序列化
     */
    private List<String> parseSensorTypes(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse sensor_types JSON: {}", json, e);
            return new ArrayList<>();
        }
    }

    /**
     * LocalDateTime → RFC3339 字符串格式化
     */
    private String formatTime(LocalDateTime dt) {
        if (dt == null) return null;
        return dt.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * 从文件名中提取切片名称（去掉扩展名）
     */
    private String extractFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        return dotIndex > 0 ? originalFilename.substring(0, dotIndex) : originalFilename;
    }

    /**
     * 精确分页总数查询
     * <p>
     * 使用 countSlices XML 查询镜像 searchSlices 的所有过滤条件，
     * 确保分页总数与检索结果完全一致。
     */
    private Long estimateTotal(SliceSearchRequest request) {
        try {
            return dataSliceMapper.countSlices(request);
        } catch (Exception e) {
            log.warn("Failed to count slices, using list size", e);
            return 0L;
        }
    }

    /**
     * 判断本次检索是否携带图像语义检索条件
     */
    private boolean isImageSearchRequested(SliceSearchRequest request) {
        return request.getImageSearchMode() != null && !request.getImageSearchMode().isBlank();
    }

    /**
     * 处理图像语义检索（以文搜图）→ 回填 sliceId 集合过滤
     *
     * <p>仅处理 image_search_mode=text：调用 {@link ImageSearchService#searchByText}，
     * 从匹配帧图中提取去重的 sliceId 集合，回填到 {@link SliceSearchRequest#getSliceIds()}。
     * image / feature 模式由前端先得到 sliceId 集合经 slice_ids 传入，此处不改写。</p>
     */
    private void resolveImageSearch(SliceSearchRequest request) {
        if (!"text".equalsIgnoreCase(request.getImageSearchMode())) {
            return;
        }
        String text = request.getImageSearchText();
        if (text == null || text.isBlank()) {
            // 请求了以文搜图但缺少文本 → 视为无匹配
            request.setSliceIds("");
            return;
        }
        try {
            List<Map<String, Object>> matches = imageSearchService.searchByText(text, null);
            String ids = matches.stream()
                    .map(m -> m.get("slice_id"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .distinct()
                    .collect(Collectors.joining(","));
            request.setSliceIds(ids);
        } catch (Exception e) {
            // 搜图失败（如未启用）→ 记录日志，回填空集合避免返回全量
            log.warn("Image text search failed, returning empty sliceIds: text={}", text, e);
            request.setSliceIds("");
        }
    }
}
