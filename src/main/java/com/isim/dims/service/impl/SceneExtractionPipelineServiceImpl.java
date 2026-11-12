package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.DetectedSceneDTO;
import com.isim.dims.dto.SceneExtractionResponse;
import com.isim.dims.dto.SceneExtractionTaskResponse;
import com.isim.dims.dto.SceneListResponse;
import com.isim.dims.dto.SceneRuleDTO;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.DetectedScene;
import com.isim.dims.entity.SceneExtractionTask;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.DetectedSceneMapper;
import com.isim.dims.mapper.SceneExtractionTaskMapper;
import com.isim.dims.service.SceneExtractionPipelineService;
import com.isim.dims.service.SceneRuleService;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.ReplayTimeUtil;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 场景提取流水线 Service 实现
 *
 * <p>独立 Bean 确保 {@code processExtraction} 的 @Async 通过 Spring AOP 代理
 * 正常生效（triggerExtraction 通过 {@code @Lazy} 自注入代理调用异步方法，
 * 避免同类自调用绕过代理）。</p>
 *
 * <p>流水线步骤（当前整包导出模式）：</p>
 * <pre>
 * 1. 创建 task(PENDING) → @Async processExtraction（幂等抢占 RUNNING）
 * 2. extract_gps_trajectory.py（raw-argv, cwd=dims-python）      → progress=25
 * 3. extract_perception.py   （raw-argv, cwd=dims-python）        → progress=40
 * 4. bag_to_unified_json.py  （raw-argv, cwd=dims-python/scene_extraction）→ progress=55
 * 5. direct_scene_generator.py（直接重建路网，供后续复用）        → progress=65
 * 6. export_openscenario.py（--xodr 复用上一步路网 + 完整时间线 xosc）→ progress=92
 * 7. export_carla.py（CARLA 场景包）                            → progress=97
 * 7. SUCCESS：回写 total_scenes / output_path / finished_at
 * 8. FAILED：回写 error_msg / finished_at
 * </pre>
 *
 * <p>产物布局：{bagDir}/scene_data/{sliceId}/
 *   ego_trajectory_raw.json、perception/、scene_data.json、openscenario/{sliceId}.xosc、
 *   openscenario/{sliceId}.xodr、direct_reconstruction/、carla/{sliceId}.xosc、
 *   carla/{sliceId}.xodr、carla/{sliceId}.json、carla/manifest.json。</p>
 *
 * <p>状态机：PENDING → RUNNING → SUCCESS | FAILED（FAILED 允许重试 1 次，
 * 同切片 FAILED ≥ 2 次 → 409 SCENE_EXTRACTION_RETRY_EXCEEDED）。</p>
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneExtractionPipelineServiceImpl implements SceneExtractionPipelineService {

    /** 场景提取 Python 脚本超时（秒）：15 分钟 */
    private static final int PYTHON_TIMEOUT_SEC = 900;

    /** scene_detector.py 超时（秒）：规则引擎全量检测，同常规脚本（15 分钟） */
    private static final int SCENE_DETECTOR_TIMEOUT_SEC = 900;

    /** 同切片 FAILED 任务数 ≥ 2 → 禁止再触发（允许重试 1 次） */
    private static final int MAX_FAILED_RETRIES = 2;

    /**
     * 整包导出模式的合成场景类型：流水线不再跑 scene_detector.py，
     * 一个数据包对应一个导出单元，详情页场景列表按该类型展示 1 条。
     */
    private static final String SCENE_TYPE_FULL_EXPORT = "full_export";

    /** RUNNING 任务卡死阈值（分钟）：JVM 被杀等导致永久 RUNNING 时，
     *  重新触发自动将其标记 FAILED 并允许创建新任务 */
    private static final int STALE_TASK_TIMEOUT_MIN = 30;

    /** v2 配置驱动规则格式版本 */
    private static final int RULE_VERSION_V2 = 2;

    /** 引擎版本兜底值（内置规则 min_engine_version） */
    private static final String DEFAULT_ENGINE_VERSION = "2.0";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final SceneExtractionTaskMapper taskMapper;
    private final DetectedSceneMapper sceneMapper;
    private final DataSliceMapper dataSliceMapper;
    private final SceneRuleService sceneRuleService;
    private final PythonExecutor pythonExecutor;
    private final StorageAccess storageAccess;
    private final DetectedScenePersistenceService scenePersistenceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** dims-python 脚本目录（extract_gps_trajectory / extract_perception） */
    @Value("${dims.python.script-dir:../dims-python}")
    private String dimsPythonDir;

    /** dims-python/scene_extraction 脚本目录（bag_to_unified_json / scene_detector / rules） */
    @Value("${dims.scene-extraction.script-dir:../dims-python/scene_extraction}")
    private String sceneToolDir;

    /** 按 slice 的锁（同切片任务创建串行化，不同切片互不影响） */
    private final Map<String, LockEntry> sliceLocks = new ConcurrentHashMap<>();

    /** 锁条目数量上限：超过后清理闲置条目（防永久驻留） */
    private static final int MAX_SLICE_LOCKS = 256;

    /** 锁条目闲置判定时长：30 分钟无访问即视为可清理 */
    private static final long LOCK_IDLE_NANOS = TimeUnit.MINUTES.toNanos(30);

    /**
     * 自引用代理（@Lazy 延迟注入，避免循环依赖）。
     * triggerExtraction 通过它调用 @Async 的 processExtraction，
     * 确保 @Async 拦截器经 AOP 代理生效（同类自调用会绕过代理）。
     */
    @Autowired
    @Lazy
    private SceneExtractionPipelineService self;

    // ========================================================================
    // 触发任务
    // ========================================================================

    @Override
    public SceneExtractionResponse triggerExtraction(String sliceId) {
        // 1. 切片存在性校验
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }

        // 2. 同切片任务创建串行化（check-then-act 原子性）
        Object lock = getSliceLock(sliceId);
        synchronized (lock) {
            // 2.1 同切片已有进行中任务（PENDING|RUNNING）→ 409
            List<SceneExtractionTask> inFlight = taskMapper.selectList(
                    new LambdaQueryWrapper<SceneExtractionTask>()
                            .eq(SceneExtractionTask::getSliceId, sliceId)
                            .in(SceneExtractionTask::getStatus, STATUS_PENDING, STATUS_RUNNING)
                            .orderByDesc(SceneExtractionTask::getCreatedAt)
            );
            if (!inFlight.isEmpty()) {
                SceneExtractionTask head = inFlight.get(0);
                // 卡死恢复：RUNNING 超过阈值（JVM 被杀等）→ 自动标记 FAILED，
                // 允许创建新任务，避免该切片后续触发永远 409
                boolean stale = STATUS_RUNNING.equals(head.getStatus())
                        && head.getCreatedAt() != null
                        && head.getCreatedAt().isBefore(
                                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(STALE_TASK_TIMEOUT_MIN));
                if (stale) {
                    log.warn("Stale RUNNING extraction task auto-failed ({} min timeout): "
                                    + "taskId={}, sliceId={}",
                            STALE_TASK_TIMEOUT_MIN, head.getId(), sliceId);
                    head.setStatus(STATUS_FAILED);
                    head.setErrorMsg("STALE_TASK_TIMEOUT: task stayed RUNNING over "
                            + STALE_TASK_TIMEOUT_MIN + " minutes, auto-failed on re-trigger");
                    head.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
                    taskMapper.updateById(head);
                } else {
                    throw new RuntimeException("SCENE_EXTRACTION_IN_PROGRESS: slice " + sliceId
                            + " has in-flight extraction task " + head.getId()
                            + " (" + head.getStatus() + "), "
                            + "same slice allows only one extraction task at a time");
                }
            }

            // 2.2 FAILED 重试限制：同切片 FAILED ≥ 2 次 → 409（仅允许重试 1 次）
            Long failedCount = taskMapper.selectCount(
                    new LambdaQueryWrapper<SceneExtractionTask>()
                            .eq(SceneExtractionTask::getSliceId, sliceId)
                            .eq(SceneExtractionTask::getStatus, STATUS_FAILED)
            );
            if (failedCount != null && failedCount >= MAX_FAILED_RETRIES) {
                throw new RuntimeException("SCENE_EXTRACTION_RETRY_EXCEEDED: slice " + sliceId
                        + " has " + failedCount + " FAILED tasks (max 1 retry)");
            }

            // 3. 创建 PENDING 任务
            SceneExtractionTask task = new SceneExtractionTask();
            task.setId(UUID.randomUUID().toString());
            task.setSliceId(sliceId);
            task.setStatus(STATUS_PENDING);
            task.setProgress(0);
            task.setTotalScenes(0);
            taskMapper.insert(task);

            // 4. 委派异步流水线（@Lazy 自引用代理，@Async 经 AOP 生效；
            //    插入已自动提交，行对异步线程可见）
            self.processExtraction(task);

            log.info("Scene extraction task created: taskId={}, sliceId={}", task.getId(), sliceId);
            return toTaskResponse(task);
        }
    }

    // ========================================================================
    // 异步流水线
    // ========================================================================

    @Override
    @Async("sceneExtractExecutor")
    public void processExtraction(SceneExtractionTask task) {
        String taskId = task.getId();
        String sliceId = task.getSliceId();
        log.info("Scene extraction pipeline started: taskId={}, sliceId={}", taskId, sliceId);

        // 幂等抢占：仅 PENDING 任务可进入 RUNNING（并发 dispatch 只执行一次）
        SceneExtractionTask latest = taskMapper.selectById(taskId);
        if (latest == null || !STATUS_PENDING.equals(latest.getStatus())) {
            log.warn("Scene extraction skipped (task not PENDING): taskId={}", taskId);
            return;
        }
        List<String> intermediatePaths = new ArrayList<>();
        try {
            // 幂等抢占状态写入纳入 try：写入失败同样落 FAILED 终态（见 catch），
            // 避免任务卡在 PENDING/RUNNING 无终态
            latest.setStatus(STATUS_RUNNING);
            latest.setProgress(10);
            taskMapper.updateById(latest);

            // 产物目录 scene_data/{sliceId}/（key 规范；minio 模式先写 staging，成功后上传）
            Path sceneDir;
            if (storageAccess.isMinioMode()) {
                sceneDir = storageAccess.stageDir("scene_data").resolve(sliceId);
                storageAccess.deleteRecursively(sceneDir); // 清理上次失败遗留 staging
            } else {
                sceneDir = storageAccess.resolveLocalPath(StorageKeys.sceneDataPrefix(sliceId));
            }
            Files.createDirectories(sceneDir);
            // bag 物化为本地路径（local 零拷贝 / minio 下载缓存），Python 进程消费
            String bagPath = resolveBagPath(sliceId);

            // ---- Step 2: GPS 轨迹提取（raw-argv，cwd=dims-python）----
            Path egoPath = sceneDir.resolve("ego_trajectory_raw.json");
            Map<String, Object> gpsArgs = new HashMap<>();
            gpsArgs.put("bag_path", bagPath);
            gpsArgs.put("output_path", egoPath.toString());
            // extract_gps_trajectory.py 仅支持 --input/标准输入，参数文件由
            // 本服务写入产物目录（持久化 byproduct，便于排查）
            Path gpsArgsFile = sceneDir.resolve("gps_args.json");
            objectMapper.writeValue(gpsArgsFile.toFile(), gpsArgs);
            pythonExecutor.executeRaw(
                    List.of("extract_gps_trajectory.py", "--input", gpsArgsFile.toString()),
                    dimsPythonDir, PYTHON_TIMEOUT_SEC);
            intermediatePaths.add(egoPath.toString());
            updateProgress(latest, 25);

            // ---- Step 3: 感知目标提取（raw-argv，cwd=dims-python）----
            Path perceptionDir = sceneDir.resolve("perception");
            Map<String, Object> perceptionArgs = new HashMap<>();
            perceptionArgs.put("bag_path", bagPath);
            perceptionArgs.put("output_dir", perceptionDir.toString());
            Path perceptionArgsFile = sceneDir.resolve("perception_args.json");
            objectMapper.writeValue(perceptionArgsFile.toFile(), perceptionArgs);
            pythonExecutor.executeRaw(
                    List.of("extract_perception.py", "--input", perceptionArgsFile.toString()),
                    dimsPythonDir, PYTHON_TIMEOUT_SEC);
            intermediatePaths.add(perceptionDir.toString());
            updateProgress(latest, 40);

            // ---- Step 4: 统一格式转换（raw-argv，cwd=dims-python/scene_extraction）----
            Path sceneDataPath = sceneDir.resolve("scene_data.json");
            pythonExecutor.executeRaw(
                    List.of("bag_to_unified_json.py",
                            "--ego", egoPath.toString(),
                            "--objects", perceptionDir.toString(),
                            "--bag-id", sliceId,
                            "--output", sceneDataPath.toString()),
                    sceneToolDir, PYTHON_TIMEOUT_SEC);
            intermediatePaths.add(sceneDataPath.toString());
            updateProgress(latest, 55);

            // ---- Step 5: 直接重建（多截面+junction 路网，先于 OpenSCENARIO 导出）----
            // CARLA 必须复用 direct reconstruction 的多截面路网和质量过滤轨迹；
            // 从 scene_data.json 重新构建会丢失这些已验证的修正。
            // 先跑本步产出路网，后续 export_openscenario 以 --xodr 复用，
            // 避免读包+landmark 融合+路口建模重复两遍（~10-18s/slice）。
            updateProgress(latest, 65);
            Path directReconstructionDir = sceneDir.resolve("direct_reconstruction");
            pythonExecutor.executeRaw(
                    List.of("scene_reconstruction/direct_scene_generator.py",
                            "--bag-path", bagPath,
                            "--output-dir", directReconstructionDir.toString(),
                            "--write-debug-json"),
                    dimsPythonDir, PYTHON_TIMEOUT_SEC);
            intermediatePaths.add(directReconstructionDir.toString());
            Path directXodr;
            try (java.util.stream.Stream<Path> stream = Files.list(directReconstructionDir)) {
                directXodr = stream
                        .filter(p -> p.getFileName().toString().endsWith(".xodr"))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(
                                "direct_scene_generator produced no xodr in " + directReconstructionDir));
            }

            // ---- Step 6: 全量数据包 OpenSCENARIO/OpenDRIVE 导出 ----
            // 不再运行 scene_detector.py，也不读取数据库规则。导出器直接
            // 使用 scene_data.json 的完整时间线生成一组 xosc；xodr 复用
            // 上一步 direct_reconstruction 的路网（同一 UTM 锚点：两管线
            // 均以 bag 首个 ego 样本为原点）。
            Path openscenarioDir = sceneDir.resolve("openscenario");
            String exportResultJson = pythonExecutor.executeRaw(
                    List.of("export_openscenario.py",
                            "--scene-data", sceneDataPath.toString(),
                            "--output-dir", openscenarioDir.toString(),
                            "--xodr", directXodr.toString()),
                    sceneToolDir, PYTHON_TIMEOUT_SEC);
            intermediatePaths.add(openscenarioDir.toString());
            JsonNode exportResult = objectMapper.readTree(exportResultJson);
            if (!exportResult.path("success").asBoolean(false)
                    || exportResult.path("files").size() < 1) {
                throw new RuntimeException("export_openscenario.py failed: " + exportResultJson);
            }
            int totalScenes = 1; // 一个数据包对应一个导出单元
            updateProgress(latest, 92);

            Path carlaDir = sceneDir.resolve("carla");
            String carlaResultJson = pythonExecutor.executeRaw(
                    List.of("export_carla.py",
                            "--direct-reconstruction-dir", directReconstructionDir.toString(),
                            "--output-dir", carlaDir.toString()),
                    sceneToolDir, PYTHON_TIMEOUT_SEC);
            intermediatePaths.add(carlaDir.toString());
            JsonNode carlaResult = objectMapper.readTree(carlaResultJson);
            if (!carlaResult.path("success").asBoolean(false)
                    || carlaResult.path("files").size() != 4) {
                throw new RuntimeException("export_carla.py failed: " + carlaResultJson);
            }
            updateProgress(latest, 97);

            // 场景识别结果不再参与流程，也不写入 detected_scene 表。
            // 产物通过 task.output_path/intermediate_paths 提供给调用方。
            updateProgress(latest, 95);

            // ---- Step 8: SUCCESS 终态（minio 模式：产物引用改写为相对 key 后上传 staging）----
            latest.setStatus(STATUS_SUCCESS);
            latest.setProgress(100);
            latest.setTotalScenes(totalScenes);
            if (storageAccess.isMinioMode()) {
                // 契约：output_path/intermediate_paths 为相对 key（无前导 /），
                // dirname(output_path)=产物目录，兄弟产物同目录 → 相对 sceneDir 推导即可
                String prefix = StorageKeys.sceneDataPrefix(sliceId);
                latest.setOutputPath(prefix + "openscenario/" + sliceId + ".xosc");
                List<String> keyPaths = new ArrayList<>();
                for (String p : intermediatePaths) {
                    keyPaths.add(prefix + sceneDir.relativize(Paths.get(p)).toString().replace('\\', '/'));
                }
                latest.setIntermediatePaths(keyPaths);
                storageAccess.commitStagedDir(sceneDir, prefix);
            } else {
                latest.setOutputPath(openscenarioDir.resolve(sliceId + ".xosc").toString());
                latest.setIntermediatePaths(intermediatePaths);
            }
            latest.setRuleVersion(null);
            latest.setEngineVersion(null);
            latest.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(latest);
            log.info("Scene extraction completed: taskId={}, sliceId={}, totalScenes={}, inserted={}",
                    taskId, sliceId, totalScenes, 0);

        } catch (Exception e) {
            // ---- Step 9: FAILED 终态（写入失败仅记录日志，不掩盖原始异常）----
            log.error("Scene extraction FAILED: taskId={}, sliceId={}", taskId, sliceId, e);
            try {
                latest.setStatus(STATUS_FAILED);
                latest.setErrorMsg(truncate(e.getMessage(), 2000));
                latest.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
                taskMapper.updateById(latest);
            } catch (Exception ex) {
                log.error("Failed to persist FAILED terminal state, task may stay RUNNING: "
                        + "taskId={}", taskId, ex);
            }
        }
    }

    // ========================================================================
    // 查询
    // ========================================================================

    @Override
    public SceneExtractionTaskResponse getTask(String taskId) {
        SceneExtractionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("TASK_NOT_FOUND: " + taskId);
        }
        return toTaskDetailResponse(task);
    }

    @Override
    public SceneListResponse listScenes(String sliceId, int page, int pageSize) {
        int p = page <= 0 ? DEFAULT_PAGE : page;
        int ps = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        SceneListResponse resp = new SceneListResponse();
        // 只展示最新 SUCCESS 任务产出的场景（重跑/重试后旧任务场景不再展示）
        SceneExtractionTask latestSuccess = taskMapper.selectOne(
                new LambdaQueryWrapper<SceneExtractionTask>()
                        .eq(SceneExtractionTask::getSliceId, sliceId)
                        .eq(SceneExtractionTask::getStatus, STATUS_SUCCESS)
                        .orderByDesc(SceneExtractionTask::getCreatedAt)
                        .last("LIMIT 1")
        );
        if (latestSuccess == null) {
            resp.setItems(new ArrayList<>());
            resp.setTotal(0L);
            resp.setPage(p);
            resp.setPageSize(ps);
            return resp;
        }

        String taskId = latestSuccess.getId();
        Long total = sceneMapper.selectCount(
                new LambdaQueryWrapper<DetectedScene>()
                        .eq(DetectedScene::getSliceId, sliceId)
                        .eq(DetectedScene::getTaskId, taskId));
        List<DetectedScene> rows = sceneMapper.selectList(
                new LambdaQueryWrapper<DetectedScene>()
                        .eq(DetectedScene::getSliceId, sliceId)
                        .eq(DetectedScene::getTaskId, taskId)
                        .orderByAsc(DetectedScene::getStartTime)
                        .last("LIMIT " + ps + " OFFSET " + (long) (p - 1) * ps)
        );

        // 整包导出模式兜底：流水线已不再运行 scene_detector.py，也不写 detected_scene 表
        // （一个数据包 = 一个导出单元）。若此时直接返回空列表，会出现「状态栏显示提取成功、
        // OpenX/CARLA 也能下载，但场景列表永远为空」的自相矛盾。这里用导出单元合成 1 条场景。
        if (total == null || total == 0L) {
            DetectedSceneDTO exportUnit = buildExportUnitScene(sliceId, latestSuccess);
            if (exportUnit != null) {
                resp.setItems(new ArrayList<>(List.of(exportUnit)));
                resp.setTotal(1L);
                resp.setPage(p);
                resp.setPageSize(ps);
                return resp;
            }
        }

        List<DetectedSceneDTO> items = new ArrayList<>();
        if (rows != null) {
            for (DetectedScene row : rows) {
                items.add(toSceneDTO(row));
            }
        }
        resp.setItems(items);
        resp.setTotal(total == null ? 0L : total);
        resp.setPage(p);
        resp.setPageSize(ps);
        return resp;
    }

    /**
     * 整包导出模式下把「导出单元」合成一条场景，供详情页场景列表展示。
     *
     * <p>与规则化场景（detected_scene 表）的区别：本条不代表某个被切分出的驾驶事件，
     * 而是本次导出覆盖的完整时间线（即切片本身的时间范围），参数段标注为整包导出。</p>
     */
    private DetectedSceneDTO buildExportUnitScene(String sliceId, SceneExtractionTask task) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            return null;
        }
        DetectedSceneDTO dto = new DetectedSceneDTO();
        // 合成 ID：无 detected_scene 主键，用任务 ID 派生，保证同一任务重复查询稳定
        dto.setSceneId("pkg-" + task.getId());
        dto.setSliceId(sliceId);
        dto.setSceneType(SCENE_TYPE_FULL_EXPORT);
        dto.setStartTime(ReplayTimeUtil.formatLocalDateTime(slice.getStartTs()));
        dto.setEndTime(ReplayTimeUtil.formatLocalDateTime(slice.getEndTs()));
        dto.setDurationS(slice.getDurationMs() == null ? null : slice.getDurationMs() / 1000.0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("export_mode", "full_package");
        params.put("note", "整包导出：1 个数据包 = 1 个导出单元，未按规则切分场景");
        if (task.getOutputPath() != null && !task.getOutputPath().isBlank()) {
            params.put("openscenario", task.getOutputPath());
        }
        dto.setParameters(params);
        // 整包导出不做置信度评估，固定 1.0
        dto.setConfidence(1.0);
        dto.setRuleId(null);
        dto.setCreatedAt(ReplayTimeUtil.formatLocalDateTime(task.getFinishedAt()));
        return dto;
    }

    @Override
    public SceneExtractionTaskResponse getLatestTaskBySliceId(String sliceId) {
        SceneExtractionTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<SceneExtractionTask>()
                        .eq(SceneExtractionTask::getSliceId, sliceId)
                        .orderByDesc(SceneExtractionTask::getCreatedAt)
                        .last("LIMIT 1")
        );
        return task != null ? toTaskDetailResponse(task) : null;
    }

    @Override
    public String readProcessFile(String sliceId, String path) {
        // 直接查该切片最新任务（避开 selectById 偶发返回 null 的问题）
        SceneExtractionTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<SceneExtractionTask>()
                        .eq(SceneExtractionTask::getSliceId, sliceId)
                        .orderByDesc(SceneExtractionTask::getCreatedAt)
                        .last("LIMIT 1"));
        if (task == null) {
            throw new RuntimeException("NO_EXTRACTION_TASK: " + sliceId);
        }
        // 归档拦截（minio 模式 + storage_type=LOCAL → StorageArchivedException → 410）：
        // readProcessFile 为数据内容读取类端点
        storageAccess.assertNotArchived(dataSliceMapper.selectById(task.getSliceId()));

        // 安全校验：path 必须在任务允许的文件列表中（key 空间 allow-list 前缀校验，
        // 统一使用 "/" 分隔，local/minio 两模式一致；兼容存量绝对路径形式的引用）。
        // 路径穿越防御（Review S1）：
        //   1) 归一化前字符串层拒绝含 ".." 路径段的输入（字符串前缀匹配无法拦截
        //      "../.." 归一化逃逸，必须先做原始校验）
        //   2) 归一化后（绝对/相对 key 统一 toAbsolutePath().normalize()）必须位于
        //      任一允许条目（或其目录）之内 —— Path.startsWith 为路径段级比较，
        //      替代原字符串前缀匹配，杜绝前缀同构路径（scene_data/x vs scene_data/x2）误放行
        if (path == null || path.isBlank() || containsDotDotSegment(path)) {
            log.warn("readProcessFile rejected (invalid or dot-dot path): sliceId={}, path={}", sliceId, path);
            throw new RuntimeException("FILE_NOT_ALLOWED: " + path);
        }
        java.util.Set<String> allowed = new java.util.HashSet<>();
        if (task.getOutputPath() != null) {
            allowed.add(task.getOutputPath());
        }
        if (task.getIntermediatePaths() != null) {
            allowed.addAll(task.getIntermediatePaths());
        }
        Path normalized = Path.of(path).toAbsolutePath().normalize();
        // 也允许 output_path 同目录下的子路径（如 openscenario/*.xosc 等后续产物）
        boolean pathAllowed = false;
        for (String a : allowed) {
            Path allowedNorm = Path.of(a).toAbsolutePath().normalize();
            if (normalized.equals(allowedNorm) || normalized.startsWith(allowedNorm)) {
                pathAllowed = true;
                break;
            }
        }
        // 额外：允许 output_path 所在目录下的任意文件（如 openscenario/ 子目录）
        if (!pathAllowed && task.getOutputPath() != null) {
            String parentDir = task.getOutputPath().replaceAll("/[^/]+$", "");
            Path parentNorm = Path.of(parentDir).toAbsolutePath().normalize();
            if (normalized.startsWith(parentNorm)) {
                pathAllowed = true;
            }
        }
        if (!pathAllowed) {
            throw new RuntimeException("FILE_NOT_ALLOWED: " + path);
        }
        // path 接受原值或 dirname 推导值（allow-list 已校验）：local 为绝对路径，minio 为相对 key
        return storageAccess.readString(path);
    }

    @Override
    public DetectedSceneDTO getScene(String sceneId) {
        DetectedScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new RuntimeException("SCENE_NOT_FOUND: " + sceneId);
        }
        return toSceneDTO(scene);
    }

    // ========================================================================
    // 流水线步骤辅助
    // ========================================================================

    /**
     * 解析 bag 路径
     *
     * <p>不做 source_type 区分：CFDI 流水线会把上传包转换为 ROS1 bag 并回写
     * filePath，转换产物与普通上传的 bag 等价，均可参与场景提取。
     * 此处仅做轻量校验：路径存在且文件名以 .bag 结尾（防手工触发非 bag 切片
     * 产生无意义任务）；深度格式校验由提取脚本承担，失败时任务自然 FAILED。</p>
     */
    /** 校验路径是否含 ".." 路径段（Path 名称元素级比较，防 "../.." 归一化逃逸） */
    private static boolean containsDotDotSegment(String path) {
        for (Path segment : Path.of(path)) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private String resolveBagPath(String sliceId) {
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null || slice.getFilePath() == null || slice.getFilePath().isBlank()) {
            throw new RuntimeException("SLICE_NOT_FOUND: " + sliceId);
        }
        // 归档拦截（minio 模式 + storage_type=LOCAL → StorageArchivedException → 410）：
        // 场景提取为数据内容消费，归档切片不可提取
        storageAccess.assertNotArchived(slice);
        String ref = slice.getFilePath();
        if (!storageAccess.exists(ref)) {
            throw new RuntimeException("BAG_NOT_FOUND: bag file does not exist: " + ref);
        }
        String fileName = ref.substring(ref.lastIndexOf('/') + 1);
        if (!fileName.endsWith(".bag")) {
            throw new RuntimeException("INVALID_STATE: file is not a ROS1 bag: " + ref);
        }
        // Python 进程只能消费本地文件：物化（local 零拷贝 / minio 下载缓存）
        return storageAccess.materializeLocal(ref).toString();
    }

    /**
     * 规则准备：优先导出 DB 启用规则为 v2 YAML 临时目录；
     * 无启用规则时兜底到内置 dims-python/scene_extraction/rules/ 目录。
     */
    private RulePlan prepareRules() throws IOException {
        Path tempRulesDir = null;
        try {
            tempRulesDir = sceneRuleService.writeRulesToTempDir();
            // scene_type → rule_id 映射（结果持久化时关联规则版本追溯）
            Map<String, String> ruleIdsByType = new HashMap<>();
            String engineVersion = DEFAULT_ENGINE_VERSION;
            for (SceneRuleDTO rule : sceneRuleService.list(true)) {
                if (rule.getId() != null && rule.getSceneType() != null) {
                    ruleIdsByType.put(rule.getSceneType(), rule.getId());
                }
                if (rule.getMinEngineVersion() != null
                        && compareVersions(rule.getMinEngineVersion(), engineVersion) > 0) {
                    engineVersion = rule.getMinEngineVersion();
                }
            }
            log.info("Scene extraction: using DB scene rules from temp dir {}", tempRulesDir);
            return new RulePlan(tempRulesDir, ruleIdsByType, RULE_VERSION_V2, engineVersion, tempRulesDir);
        } catch (IllegalStateException e) {
            // 无启用规则 → 内置规则目录（工具仓库自带，只读不清理）
            log.warn("Scene extraction: no enabled scene rules in DB ({}), "
                    + "fallback to built-in rules dir", e.getMessage());
            Path builtin = Paths.get(sceneToolDir, "rules");
            return new RulePlan(builtin, Map.of(), RULE_VERSION_V2, DEFAULT_ENGINE_VERSION, null);
        }
    }

    /**
     * 解析 detected_scenes.json 并事务性替换 detected_scene 表中的旧数据
     *
     * <p>重跑/重试时先 DELETE 该切片旧场景再批量 INSERT 新数据（同一事务），
     * 防止 GET /v1/data-slices/{id}/scenes 返回重复数据。</p>
     *
     * <p>逐行容错：单条时间格式异常/字段缺失/类型不匹配的记录仅跳过并 warn
     * （记录行号），不中断正常行的持久化，避免单条畸形记录致整体 FAILED。</p>
     *
     * @return 插入的场景条数
     */
    private int persistDetectedScenes(SceneExtractionTask task, Path detectedScenesPath,
                                      Map<String, String> ruleIdsByType) throws IOException {
        if (!Files.exists(detectedScenesPath)) {
            throw new RuntimeException("scene_detector.py did not produce detected_scenes.json: "
                    + detectedScenesPath);
        }
        JsonNode root = objectMapper.readTree(Files.readString(detectedScenesPath));
        JsonNode scenes = root.path("detected_scenes");
        if (!scenes.isArray()) {
            throw new RuntimeException("detected_scenes.json missing detected_scenes array: "
                    + detectedScenesPath);
        }
        List<DetectedScene> batch = new ArrayList<>();
        int index = 0;
        for (JsonNode node : scenes) {
            index++;
            try {
                DetectedScene scene = new DetectedScene();
                scene.setTaskId(task.getId());
                scene.setSliceId(task.getSliceId());
                scene.setExternalSceneId(textOrNull(node, "scene_id"));
                scene.setSceneType(textOrNull(node, "scene_type"));
                scene.setStartTime(parseRfc3339(textOrNull(node, "start_time")));
                scene.setEndTime(parseRfc3339(textOrNull(node, "end_time")));
                scene.setDurationS(numberOrNull(node, "duration_s"));
                scene.setConfidence(numberOrNull(node, "confidence"));
                if (node.has("parameters") && node.get("parameters").isObject()) {
                    scene.setParameters(objectMapper.convertValue(node.get("parameters"),
                            new TypeReference<Map<String, Object>>() {}));
                }
                String sceneType = scene.getSceneType();
                scene.setRuleId(sceneType != null ? ruleIdsByType.get(sceneType) : null);
                batch.add(scene);
            } catch (Exception e) {
                log.warn("Malformed detected_scenes.json row {} skipped (taskId={}): {}",
                        index, task.getId(), e.getMessage());
            }
        }
        // 事务性替换：先 DELETE 旧场景再批量 INSERT（L2：批量而非逐行 INSERT）
        scenePersistenceService.replaceSliceScenes(task.getSliceId(), batch);
        log.info("Scene extraction: persisted {} detected scenes, taskId={}", batch.size(), task.getId());
        return batch.size();
    }

    /** 更新任务进度（幂等，重复调用无副作用） */
    private void updateProgress(SceneExtractionTask task, int progress) {
        task.setProgress(progress);
        taskMapper.updateById(task);
    }

    // ========================================================================
    // 按 slice 锁（有上限 + 闲置清理，防永久驻留）
    // ========================================================================

    private Object getSliceLock(String sliceId) {
        cleanupStaleLocks();
        LockEntry entry = sliceLocks.computeIfAbsent(sliceId, k -> new LockEntry());
        entry.lastUsedNanos = System.nanoTime();
        return entry.lock;
    }

    /**
     * 锁表超过上限时清理闲置条目（不触碰近期使用过的锁，无竞态）
     */
    private void cleanupStaleLocks() {
        if (sliceLocks.size() < MAX_SLICE_LOCKS) {
            return;
        }
        long now = System.nanoTime();
        sliceLocks.entrySet().removeIf(e -> now - e.getValue().lastUsedNanos > LOCK_IDLE_NANOS);
        if (sliceLocks.size() >= MAX_SLICE_LOCKS) {
            log.warn("sliceLocks size {} exceeds threshold {}; stale entries reaped on idle",
                    sliceLocks.size(), MAX_SLICE_LOCKS);
        }
    }

    /** 锁条目：锁对象 + 最后使用时间（nanoTime） */
    private static final class LockEntry {
        final Object lock = new Object();
        volatile long lastUsedNanos = System.nanoTime();
    }

    // ========================================================================
    // 规则准备结果
    // ========================================================================

    /**
     * 规则准备结果：规则目录 + scene_type→rule_id 映射 + 版本信息
     */
    private static final class RulePlan {
        /** scene_detector.py --rules 参数（目录） */
        final Path rulesDir;
        /** scene_type → scene_rule 记录 UUID（内置规则时为空 map） */
        final Map<String, String> ruleIdsByType;
        /** 规则格式版本（v2 = 2） */
        final Integer ruleVersion;
        /** 最低引擎版本门禁（启用规则 max min_engine_version） */
        final String engineVersion;
        /** DB 导出的临时规则目录（需清理）；内置规则时为 null */
        final Path tempRulesDir;

        RulePlan(Path rulesDir, Map<String, String> ruleIdsByType, Integer ruleVersion,
                 String engineVersion, Path tempRulesDir) {
            this.rulesDir = rulesDir;
            this.ruleIdsByType = ruleIdsByType;
            this.ruleVersion = ruleVersion;
            this.engineVersion = engineVersion;
            this.tempRulesDir = tempRulesDir;
        }
    }

    // ========================================================================
    // DTO 转换
    // ========================================================================

    private SceneExtractionResponse toTaskResponse(SceneExtractionTask task) {
        SceneExtractionResponse resp = new SceneExtractionResponse();
        resp.setTaskId(task.getId());
        resp.setStatus(task.getStatus());
        return resp;
    }

    private SceneExtractionTaskResponse toTaskDetailResponse(SceneExtractionTask task) {
        SceneExtractionTaskResponse resp = new SceneExtractionTaskResponse();
        resp.setTaskId(task.getId());
        resp.setSliceId(task.getSliceId());
        resp.setStatus(task.getStatus());
        resp.setProgress(task.getProgress());
        resp.setSceneCount(task.getTotalScenes());
        resp.setOutputPath(task.getOutputPath());
        resp.setErrorMsg(task.getErrorMsg());
        resp.setCreatedAt(ReplayTimeUtil.formatLocalDateTime(task.getCreatedAt()));
        resp.setFinishedAt(ReplayTimeUtil.formatLocalDateTime(task.getFinishedAt()));
        resp.setIntermediatePaths(task.getIntermediatePaths());
        return resp;
    }

    private DetectedSceneDTO toSceneDTO(DetectedScene scene) {
        DetectedSceneDTO dto = new DetectedSceneDTO();
        dto.setSceneId(scene.getId());
        dto.setSliceId(scene.getSliceId());
        dto.setSceneType(scene.getSceneType());
        dto.setStartTime(ReplayTimeUtil.formatLocalDateTime(scene.getStartTime()));
        dto.setEndTime(ReplayTimeUtil.formatLocalDateTime(scene.getEndTime()));
        dto.setDurationS(scene.getDurationS());
        dto.setParameters(scene.getParameters());
        dto.setConfidence(scene.getConfidence());
        dto.setRuleId(scene.getRuleId());
        dto.setCreatedAt(ReplayTimeUtil.formatLocalDateTime(scene.getCreatedAt()));
        return dto;
    }

    // ========================================================================
    // 通用工具
    // ========================================================================

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private Double numberOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }

    /** RFC3339（如 2026-07-09T03:16:59.470771Z）→ UTC LocalDateTime */
    private LocalDateTime parseRfc3339(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toLocalDateTime();
        } catch (Exception e) {
            log.warn("Failed to parse RFC3339 time: {}", s);
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 简易 "x.y.z" 版本号比较（与 Python 引擎的 tuple 比较语义一致） */
    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? parseVersionPart(pa[i]) : 0;
            int y = i < pb.length ? parseVersionPart(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private int parseVersionPart(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void deleteRecursively(Path dir) {
        try {
            if (dir != null && Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.warn("Failed to delete temp rule file: {}", p, e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean temp rules dir: {}", dir, e);
        }
    }
}
