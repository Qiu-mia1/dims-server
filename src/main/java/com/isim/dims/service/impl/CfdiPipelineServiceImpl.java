package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.IngestionTask;
import com.isim.dims.entity.SensorStream;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.IngestionTaskMapper;
import com.isim.dims.mapper.SensorStreamMapper;
import com.isim.dims.service.CfdiConversionService;
import com.isim.dims.service.CfdiMetadataService;
import com.isim.dims.service.CfdiPipelineService;
import com.isim.dims.service.IngestionPipelineService;
import com.isim.dims.service.IngestionService;
import com.isim.dims.util.CfdiConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CFDI 数据包导入流水线 Service 实现
 *
 * 独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效。
 * 流水线步骤：
 *   CONVERTING (10→50) → [复用 IngestionPipelineService: PARSING→TAGGING→READY]
 *
 * 关键步骤失败 → ERROR；非关键步骤（h265复制、标定解析、元数据解析）→ WARN + 继续
 * @feature: cfdi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CfdiPipelineServiceImpl implements CfdiPipelineService {

    private final CfdiConversionService cfdiConversionService;
    private final CfdiMetadataService cfdiMetadataService;
    private final IngestionPipelineService ingestionPipelineService;

    private final DataSliceMapper dataSliceMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final IngestionService ingestionService;
    private final SensorStreamMapper sensorStreamMapper;

    @Value("${dims.storage.video-dir:}")
    private String videoDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 等待 IngestionTask 的最大重试次数（30 秒超时 = 60 次 × 500ms） */
    private static final int WAIT_FOR_TASK_MAX_RETRIES = 60;

    /**
     * 等待解析流水线完成的超时（毫秒）= 360s
     *
     * <p>必须大于 parse_rosbag.py 的 Python 执行超时（300s，见 dims.python.timeout-sec）
     * 并留出 sensor_stream 批量插入的裕量，否则大 bag 解析超过等待预算时
     * sensor_stream 仍为 0 条，video_file/sub_span_us 分发静默跳过，原始竞态复发。</p>
     */
    private static final long WAIT_PARSE_TIMEOUT_MS = 360_000L;

    /** 每次重试的休眠间隔（毫秒） */
    private static final long WAIT_FOR_TASK_RETRY_INTERVAL_MS = 500L;

    @Override
    @Async
    public void processCfdiPipeline(String sliceId, String tarRef) {
        log.info("Async CFDI pipeline started: sliceId={}, tarRef={}", sliceId, tarRef);

        // 等待上传事务提交后查询 IngestionTask（避免 @Transactional + @Async 竞态）
        IngestionTask task = waitForTask(sliceId);
        if (task == null) {
            log.error("IngestionTask not found for sliceId={} after retries", sliceId);
            return;
        }

        Path publicDir = null;
        String bagFilePath = null;

        try {
            // ====================================================================
            // Step: CONVERTING (progress 10→50)
            // ====================================================================

            // 更新 DataSlice 状态为 CONVERTING
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice != null) {
                slice.setStatus("CONVERTING");
                dataSliceMapper.updateById(slice);
            }

            // 1. 解压 tar.gz（tarRef 存储引用，物化到本地工作区后解压）
            ingestionService.updateTaskStatus(task, "CONVERTING", 10);
            log.info("CFDI Step 1/6: extracting tar.gz for sliceId={}", sliceId);
            publicDir = cfdiConversionService.extractTarGz(sliceId, tarRef);

            // 2. 打包为 mapping_input.tar.gz
            ingestionService.updateTaskStatus(task, "CONVERTING", 20);
            log.info("CFDI Step 2/6: repacking for Docker for sliceId={}", sliceId);
            Path tarFile = cfdiConversionService.repackForDocker(publicDir);

            // 3. Docker 转换
            ingestionService.updateTaskStatus(task, "CONVERTING", 30);
            log.info("CFDI Step 3/6: running Docker conversion for sliceId={}", sliceId);
            Path bagFile = cfdiConversionService.runConversion(tarFile, publicDir.getParent());

            // 4. 写回 bag 到持久存储（统一存储引用 "slices/{id}/original.bag"）
            ingestionService.updateTaskStatus(task, "CONVERTING", 40);
            log.info("CFDI Step 4/6: writing bag to storage for sliceId={}", sliceId);
            bagFilePath = cfdiConversionService.copyBagToStorage(bagFile, sliceId);

            // 更新 DataSlice.filePath 指向新的 .bag 文件（原指向 .tar.gz，后续信号提取/可视化依赖 .bag 路径）
            DataSlice sliceForUpdate = dataSliceMapper.selectById(sliceId);
            if (sliceForUpdate != null) {
                sliceForUpdate.setFilePath(bagFilePath);
                dataSliceMapper.updateById(sliceForUpdate);
                log.info("Updated DataSlice.filePath to bag: sliceId={}, filePath={}", sliceId, bagFilePath);
            }

            // 5. 复制 h265 视频（非关键，失败仅 WARN）
            ingestionService.updateTaskStatus(task, "CONVERTING", 45);
            log.info("CFDI Step 5/6: copying h265 videos for sliceId={}", sliceId);
            try {
                cfdiConversionService.copyH265Videos(publicDir, sliceId);
            } catch (Exception e) {
                log.warn("Non-critical: h265 video copy failed for sliceId={}", sliceId, e);
            }

            // 6. 解析标定和元数据（非关键，失败仅 WARN）
            log.info("CFDI Step 6/6: parsing metadata for sliceId={}", sliceId);
            try {
                String publicDirPath = publicDir.toAbsolutePath().toString();

                // 摄像头标定（Python 脚本需要 public_dir 指向 public/ 目录）
                cfdiMetadataService.parseCameraCalibration(sliceId, publicDirPath);

                // CFDI 元数据（同样需要 public_dir）
                cfdiMetadataService.parseCfdiMetadata(sliceId, publicDirPath);
            } catch (Exception e) {
                log.warn("Non-critical: metadata parsing failed for sliceId={}", sliceId, e);
            }

            // ====================================================================
            // Step: PARSING → TAGGING → READY（复用 IngestionPipelineService）
            // ====================================================================
            ingestionService.updateTaskStatus(task, "CONVERTING", 50);
            log.info("CFDI pipeline: delegating to parse pipeline for sliceId={}", sliceId);

            // 委托解析流水线（PARSING → TAGGING → READY，在 PARSING 阶段创建 sensor_stream 记录）
            ingestionPipelineService.processParsePipeline(sliceId, bagFilePath);

            // 等待解析流水线完成（parse 流水线 @Async，需轮询等待）：
            // 所有 sensor_stream 记录在 PARSING 阶段循环插入完成后才会置 TAGGING，
            // 因此等待 IngestionTask 到达 TAGGING/READY 才能保证流记录完整
            waitForParseCompletion(sliceId);

            // 防回归校验：解析流水线会整表更新 DataSlice，
            // 确保 file_path 仍指向实际的 .bag 文件（而非已删除的 tar.gz）
            ensureBagFilePath(sliceId, bagFilePath);

            // 解析流水线完成后，更新 sensor_stream metadata 添加 video_file 引用
            //（此时 sensor_stream 记录已存在，h265 文件已就位）
            try {
                updateSensorStreamVideoFiles(sliceId);
            } catch (Exception e) {
                log.warn("Non-critical: updateSensorStreamVideoFiles failed for sliceId={}", sliceId, e);
            }

            // sub_span_us 分发（必须在 sensor_stream 记录创建后执行）
            try {
                cfdiMetadataService.distributeSubSpanUs(sliceId);
            } catch (Exception e) {
                log.warn("Non-critical: distributeSubSpanUs failed for sliceId={}", sliceId, e);
            }

            log.info("CFDI pipeline delegation complete for sliceId={}", sliceId);

        } catch (Exception e) {
            log.error("CFDI pipeline FAILED for sliceId={}", sliceId, e);

            // 更新切片的错误状态
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice != null) {
                slice.setStatus("ERROR");
                slice.setErrorMsg(e.getMessage());
                dataSliceMapper.updateById(slice);
            }

            // 更新任务错误状态
            if (task != null) {
                task.setErrorMsg(e.getMessage());
                ingestionService.updateTaskStatus(task, "ERROR", 0);
            }
        } finally {
            // 清理临时文件
            try {
                cfdiConversionService.cleanupTempDir(sliceId);
            } catch (Exception e) {
                log.warn("Failed to clean temp dir for sliceId={}", sliceId, e);
            }
        }
    }

    /**
     * 防回归校验：确保 data_slice.file_path 指向转换后的 .bag 文件
     *
     * <p>转换完成后 file_path 必须为 {@code {bagDir}/{sliceId}/original.bag}。
     * 解析流水线会对 DataSlice 做整表 updateById，若出现路径漂移
     * （如回写为已删除的 original.tar.gz 路径），此处立即修正。</p>
     *
     * @param sliceId         切片 UUID
     * @param expectedBagPath 转换产物的标准 .bag 路径
     */
    private void ensureBagFilePath(String sliceId, String expectedBagPath) {
        if (expectedBagPath == null || expectedBagPath.isBlank()) {
            log.warn("ensureBagFilePath skipped: expectedBagPath is empty for sliceId={}", sliceId);
            return;
        }
        try {
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            if (slice == null) {
                log.warn("ensureBagFilePath skipped: DataSlice not found for sliceId={}", sliceId);
                return;
            }
            if (!expectedBagPath.equals(slice.getFilePath())) {
                log.warn("file_path drift detected for sliceId={}: stored={}, expected={}, fixing",
                        sliceId, slice.getFilePath(), expectedBagPath);
                slice.setFilePath(expectedBagPath);
                dataSliceMapper.updateById(slice);
            }
        } catch (Exception e) {
            log.warn("ensureBagFilePath failed for sliceId={}", sliceId, e);
        }
    }

    /**
     * 等待解析流水线完成，确保 sensor_stream 记录全部插入
     *
     * <p>判定依据（修复等待竞态）：parse 流水线在 PARSING 阶段逐条插入
     * sensor_stream 记录，全部插入完成后才调用 updateTaskStatus 置 TAGGING，
     * 因此以 IngestionTask 到达 TAGGING/READY 作为"流记录完整"信号，
     * 而非旧逻辑的 count > 0（第一条流插入即返回，导致后续摄像头流的
     * video_file/sub_span_us 分发静默丢失）。</p>
     *
     * <p>超时预算（二次审查修复）：时间预算 {@link #WAIT_PARSE_TIMEOUT_MS}（360s）
     * 必须覆盖 parse_rosbag.py 的 300s Python 超时 + 插入裕量；
     * 若按计数上限等待，大 bag 解析超过预算时 sensor_stream 仍为 0 条，
     * 分发步骤静默跳过，原始竞态复发。</p>
     *
     * <p>兜底：计数连续 3 次轮询不变视为稳定（防状态更新竞态）；
     * ERROR 或超时则 best-effort 继续（后续分发步骤对缺失记录是安全的）。</p>
     *
     * @param sliceId 切片 UUID
     * @return true=可继续分发（流记录完整或已 ERROR/超时降级），false=异常中断
     */
    private boolean waitForParseCompletion(String sliceId) {
        long deadline = System.currentTimeMillis() + WAIT_PARSE_TIMEOUT_MS;
        Long lastCount = null;
        int stablePolls = 0;
        while (System.currentTimeMillis() < deadline) {
            // 信号 1：IngestionTask 状态到达 TAGGING/READY（流记录已全部插入）
            IngestionTask task = ingestionTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestionTask>()
                            .eq(IngestionTask::getSliceId, sliceId)
                            .last("LIMIT 1")
            );
            if (task != null) {
                String status = task.getStatus();
                if ("TAGGING".equals(status) || "READY".equals(status)) {
                    log.info("Parse pipeline reached status={} for sliceId={}, sensor_stream records complete",
                            status, sliceId);
                    return true;
                }
                if ("ERROR".equals(status)) {
                    log.warn("Parse pipeline ERROR for sliceId={}, proceeding with partial sensor_stream records",
                            sliceId);
                    return true;
                }
            }

            // 信号 2（兜底）：流计数连续 3 次轮询不变视为稳定
            long count = sensorStreamMapper.selectCount(
                    new LambdaQueryWrapper<SensorStream>().eq(SensorStream::getSliceId, sliceId)
            );
            if (lastCount != null && count == lastCount) {
                if (count > 0 && ++stablePolls >= 3) {
                    log.info("SensorStream count stable for sliceId={}: count={}", sliceId, count);
                    return true;
                }
            } else {
                stablePolls = 0;
            }
            lastCount = count;

            try {
                Thread.sleep(WAIT_FOR_TASK_RETRY_INTERVAL_MS);
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
     * 等待上传事务提交后查询 IngestionTask
     * 最多重试 60 次（30 秒），避免长事务导致的 @Transactional + @Async 竞态
     */
    private IngestionTask waitForTask(String sliceId) {
        for (int retry = 0; retry < WAIT_FOR_TASK_MAX_RETRIES; retry++) {
            IngestionTask task = ingestionTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestionTask>().eq(IngestionTask::getSliceId, sliceId)
            );
            if (task != null) return task;
            try {
                Thread.sleep(WAIT_FOR_TASK_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * 更新 sensor_stream metadata 添加 video_file 引用
     *
     * <p>对于每一路摄像头 topic，找到对应的 sensor_stream 记录，
     * 在其 metadata 中写入 {"video_file": "F30_front_far.h265"}。</p>
     *
     * <p>匹配逻辑：使用 CfdiConstants.getCfdiSensorIdFromTopic() 将 bag topic
     * 映射到 CFDI sensor ID，再通过 h265 文件名前缀关联视频文件。</p>
     *
     * <p>必须在 PARSING 阶段完成后调用（此时 sensor_stream 记录已创建）。</p>
     */
    /**
     * 更新 sensor_stream metadata 添加 video_file 引用（供 LOCAL 导入流水线复用）
     *
     * <p>对于每一路摄像头 topic，找到对应的 sensor_stream 记录，
     * 在其 metadata 中写入 {"video_file": "F30_front_far.h265"}。</p>
     */
    void updateSensorStreamVideoFiles(String sliceId) {
        List<SensorStream> streams = sensorStreamMapper.selectList(
                new LambdaQueryWrapper<SensorStream>().eq(SensorStream::getSliceId, sliceId)
        );

        for (SensorStream stream : streams) {
            String topic = stream.getTopic() != null ? stream.getTopic() : "";
            String existingMetadata = stream.getMetadata();

            // 使用 CfdiConstants 的映射从 topic 获取 CFDI sensor ID
            String cfdiSensorId = CfdiConstants.getCfdiSensorIdFromTopic(topic);
            if (cfdiSensorId == null) continue;

            // 检查该 CFDI sensor ID 是否有对应的 h265 文件
            if (!CfdiConstants.H265_SENSOR_MAP.containsKey(cfdiSensorId)) continue;

            String videoFileName = cfdiSensorId + ".h265";

            try {
                // 设置 sensor_name（冗余列，用于精确匹配 sub_span_us）
                if (stream.getSensorName() == null || stream.getSensorName().isEmpty()) {
                    stream.setSensorName(cfdiSensorId);
                }

                // 解析已有 metadata，追加 video_file
                Map<String, Object> metadataMap;
                if (existingMetadata != null && !existingMetadata.isEmpty()) {
                    metadataMap = objectMapper.readValue(existingMetadata, Map.class);
                } else {
                    metadataMap = new HashMap<>();
                }
                metadataMap.put("video_file", videoFileName);
                stream.setMetadata(objectMapper.writeValueAsString(metadataMap));
                sensorStreamMapper.updateById(stream);
                log.debug("Updated sensor_stream {} with video_file={}, sensor_name={}",
                        stream.getId(), videoFileName, cfdiSensorId);
            } catch (Exception e) {
                log.warn("Failed to update sensor_stream metadata for streamId={}", stream.getId(), e);
            }
        }
    }
}
