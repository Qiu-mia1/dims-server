package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.service.CfdiConversionService;
import com.isim.dims.service.CfdiMetadataService;
import com.isim.dims.service.IngestionPipelineService;
import com.isim.dims.service.IngestionService;
import com.isim.dims.service.LocalDirectoryImportPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地目录导入异步流水线 Service 实现
 * <p>
 * 独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效；逐会话串行处理（稳定优先）。
 * 每会话流程：
 * 1. 等待同步事务提交（waitForTask，60×500ms 轮询，复用 CFDI 流水线模式）
 * 2. 复制 {sessionDir}/public → {cfdiDir}/{sliceId}/public（源目录只读）
 * 3. CONVERTING (10→50)：repackForDocker → runConversion → copyBagToStorage
 * 4. 回写 DataSlice.filePath 为 bag 路径
 * 5. 非关键步骤（h265 复制、标定解析、元数据解析）失败仅 WARN
 * 6. 委托 IngestionPipelineService（PARSING→TAGGING→READY）+ waitForParseCompletion（360s 轮询）
 * 7. finally 清理工作区 {cfdiDir}/{sliceId}
 * 关键步骤失败 → 单会话 ERROR + 继续下一个会话。
 *
 * @feature: ingestion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalDirectoryImportPipelineServiceImpl implements LocalDirectoryImportPipelineService {

    private final DataSliceMapper dataSliceMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionService ingestionService;
    private final CfdiConversionService cfdiConversionService;
    private final CfdiMetadataService cfdiMetadataService;
    private final CfdiPipelineServiceImpl cfdiPipelineService;
    private final IngestionPipelineService ingestionPipelineService;
    private final StorageAccess storageAccess;

    /**
     * 等待 IngestionTask 的最大重试次数（30 秒超时 = 60 次 × 500ms）
     */
    private static final int WAIT_FOR_TASK_MAX_RETRIES = 60;

    /**
     * 等待解析流水线完成的超时（毫秒）= 360s，须大于 parse_rosbag.py 的 300s 执行超时
     */
    private static final long WAIT_PARSE_TIMEOUT_MS = 360_000L;

    /**
     * 每次重试的休眠间隔（毫秒）
     */
    private static final long WAIT_RETRY_INTERVAL_MS = 500L;

    @Override
    @Async("localImportExecutor")
    public void processImport(List<ImportedSession> sessions) {
        log.info("Async local directory import pipeline started: sessions={}", sessions.size());
        for (ImportedSession session : sessions) {
            processSession(session);
        }
        log.info("Local directory import pipeline finished: sessions={}", sessions.size());
    }

    @Override
    @Async("localImportExecutor")
    public void processOneImport(ImportedSession session) {
        log.info("Async local directory import pipeline started: session={}", session.getSliceId());
        processSession(session);
        log.info("Local directory import pipeline finished: session={}", session.getSliceId());
    }

    /**
     * 处理单个会话：异常隔离，失败仅置 ERROR 不影响后续会话
     */
    private void processSession(ImportedSession session) {
        String sliceId = session.getSliceId();

        // 1. 等待同步事务提交后查询 IngestionTask（避免 @Transactional + @Async 竞态）
        IngestionTask task = waitForTask(sliceId);
        if (task == null) {
            // 超时仍查不到任务：同步侧事务可能已回滚或失败。
            // 此处无 IngestionTask 实体可更新（否则 waitForTask 已返回），
            // 将 DataSlice 置 ERROR 避免僵尸 UPLOADING 状态。
            log.error("IngestionTask not found for sliceId={} after retries, marking slice ERROR", sliceId);
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice != null) {
                slice.setStatus("ERROR");
                slice.setErrorMsg("IngestionTask not found after 30s retries (import may have been rolled back)");
                dataSliceMapper.updateById(slice);
            }
            return;
        }

        Path workDir = null;
        String bagFilePath = null;
        DataSlice slice = null;
        try {
            // 2. 复制源会话 public/ 到工作区（源目录只读，不动用户数据；
            //    工作区经 StorageAccess：local → cfdiDir/{sliceId}，minio → tempDir/cfdi/{sliceId}）
            workDir = storageAccess.cfdiWorkDir(sliceId);
            Path publicDirInWork = workDir.resolve("public");
            Path sourcePublic = Paths.get(session.getSessionDirPath()).resolve("public");
            if (!Files.isDirectory(sourcePublic)) {
                throw new RuntimeException("SOURCE_MISSING: " + sourcePublic + " is not a directory");
            }
            log.info("Local import step 1: copying {} → {}", sourcePublic, publicDirInWork);
            copyDirectory(sourcePublic, publicDirInWork);

            // 3. CONVERTING: Docker 转换链路
            ingestionService.updateTaskStatus(task, "CONVERTING", 10);
            log.info("Local import step 2: repacking public/ for sliceId={}", sliceId);
            Path tarFile = cfdiConversionService.repackForDocker(publicDirInWork);

            ingestionService.updateTaskStatus(task, "CONVERTING", 30);
            log.info("Local import step 3: running Docker conversion for sliceId={}", sliceId);
            Path bagFile = cfdiConversionService.runConversion(tarFile, workDir);

            ingestionService.updateTaskStatus(task, "CONVERTING", 40);
            log.info("Local import step 4: writing bag to storage for sliceId={}", sliceId);
            bagFilePath = cfdiConversionService.copyBagToStorage(bagFile, sliceId);

            // 4. 回写 DataSlice.filePath 指向 .bag（占位路径 → 真实路径）
            slice = dataSliceMapper.selectById(sliceId);
            if (slice != null) {
                slice.setStatus("CONVERTING");
                slice.setFilePath(bagFilePath);
                dataSliceMapper.updateById(slice);
            }

            // 5. 非关键步骤：h265 视频复制（失败仅 WARN）
            ingestionService.updateTaskStatus(task, "CONVERTING", 45);
            log.info("Local import step 5: copying h265 videos for sliceId={}", sliceId);
            try {
                cfdiConversionService.copyH265Videos(publicDirInWork, sliceId);
            } catch (Exception e) {
                log.warn("Non-critical: h265 video copy failed for sliceId={}", sliceId, e);
            }

            // 6. 非关键步骤：标定 + 元数据解析（失败仅 WARN；本地目录无 meta/ 时元数据解析预期失败）
            log.info("Local import step 6: parsing calibration/metadata for sliceId={}", sliceId);
            String publicDirPath = publicDirInWork.toAbsolutePath().toString();
            try {
                cfdiMetadataService.parseCameraCalibration(sliceId, publicDirPath);
            } catch (Exception e) {
                log.warn("Non-critical: camera calibration parse failed for sliceId={}", sliceId, e);
            }
            try {
                cfdiMetadataService.parseCfdiMetadata(sliceId, publicDirPath);
            } catch (Exception e) {
                log.warn("Non-critical: cfdi metadata parse failed for sliceId={}", sliceId, e);
            }

            // 7. 委托解析流水线（PARSING → TAGGING → READY）
            ingestionService.updateTaskStatus(task, "CONVERTING", 50);
            log.info("Local import step 7: delegating to parse pipeline for sliceId={}", sliceId);
            ingestionPipelineService.processParsePipeline(sliceId, bagFilePath);

            // 8. 等待解析流水线完成（parse 流水线 @Async，轮询等待避免下游竞态）
            waitForParseCompletion(sliceId);

            // 8.5 补充 CFDI 同款链路：sub_span_us 分发 + video_file 回写（非关键，WARN 容错）
            try {
                cfdiMetadataService.distributeSubSpanUs(sliceId);
            } catch (Exception e) {
                log.warn("Non-critical: distributeSubSpanUs failed for sliceId={}", sliceId, e);
            }
            try {
                cfdiPipelineService.updateSensorStreamVideoFiles(sliceId);
            } catch (Exception e) {
                log.warn("Non-critical: updateSensorStreamVideoFiles failed for sliceId={}", sliceId, e);
            }

            log.info("Local import pipeline complete: sliceId={}", sliceId);

        } catch (Exception e) {
            log.error("Local import pipeline FAILED for sliceId={}", sliceId, e);

            slice = dataSliceMapper.selectById(sliceId);
            if (slice != null) {
                slice.setStatus("ERROR");
                slice.setErrorMsg(e.getMessage());
                dataSliceMapper.updateById(slice);
            }
            if (task != null) {
                task.setErrorMsg(e.getMessage());
                ingestionService.updateTaskStatus(task, "ERROR", 0);
            }
        } finally {
            // 9. 清理工作区
            cleanupWorkDir(workDir);
            if (null != slice) {
                log.debug("清理下载数据：{}", slice.getImportSourceDir());
                cleanupWorkDir(Path.of(slice.getImportSourceDir()));
            }
        }
    }

    /**
     * 等待上传事务提交后查询 IngestionTask（最多重试 60 次 / 30 秒）
     */
    private IngestionTask waitForTask(String sliceId) {
        for (int retry = 0; retry < WAIT_FOR_TASK_MAX_RETRIES; retry++) {
            IngestionTask task = ingestionTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestionTask>().eq(IngestionTask::getSliceId, sliceId)
            );
            if (task != null) return task;
            try {
                Thread.sleep(WAIT_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * 等待解析流水线完成（360s 预算）
     * <p>
     * parse 流水线在 PARSING 阶段逐条插入 sensor_stream、全部完成后置 TAGGING，
     * 因此以 IngestionTask 到达 TAGGING/READY 作为完成信号；ERROR 或超时则
     * best-effort 继续（后续无依赖 sensor_stream 的步骤，安全降级）。
     */
    private boolean waitForParseCompletion(String sliceId) {
        long deadline = System.currentTimeMillis() + WAIT_PARSE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            IngestionTask task = ingestionTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestionTask>()
                            .eq(IngestionTask::getSliceId, sliceId)
                            .last("LIMIT 1")
            );
            if (task != null) {
                String status = task.getStatus();
                if ("TAGGING".equals(status) || "READY".equals(status)) {
                    log.info("Parse pipeline reached status={} for sliceId={}", status, sliceId);
                    return true;
                }
                if ("ERROR".equals(status)) {
                    log.warn("Parse pipeline ERROR for sliceId={}, continuing best-effort", sliceId);
                    return true;
                }
            }
            try {
                Thread.sleep(WAIT_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Parse pipeline did not complete for sliceId={} within {}ms, continuing best-effort",
                sliceId, WAIT_PARSE_TIMEOUT_MS);
        return false;
    }

    /**
     * 递归复制目录（nio 实现；commons-io 未引入，避免新增依赖）
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(path -> {
                Path dest = target.resolve(source.relativize(path));
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * 清理工作区目录 {cfdiDir}/{sliceId}
     */
    private void cleanupWorkDir(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            log.debug("Work dir already cleaned or not exists: {}", workDir);
            return;
        }
        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete work file: {}", path, e);
                        }
                    });
            log.info("Cleaned work dir: {}", workDir);
        } catch (IOException e) {
            log.warn("Failed to clean work dir {}: {}", workDir, e.getMessage());
        }
    }
}
