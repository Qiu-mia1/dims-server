package com.isim.dims.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ExportRequest;
import com.isim.dims.entity.ExportTask;
import com.isim.dims.mapper.ExportTaskMapper;
import com.isim.dims.service.ExportPipelineService;
import com.isim.dims.util.PythonExecutor;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据导出流水线 Service 实现
 *
 * 独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效。
 * 流水线步骤：PENDING → PROCESSING → READY | ERROR
 * @feature: export
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportPipelineServiceImpl implements ExportPipelineService {

    private final ExportTaskMapper exportTaskMapper;
    private final PythonExecutor pythonExecutor;
    private final StorageAccess storageAccess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Async
    public void processExportPipeline(ExportTask task, String bagFilePath,
                                       ExportRequest request) {
        String exportId = task.getId();
        String sliceId = task.getSliceId();
        log.info("Export pipeline started: exportId={}, sliceId={}", exportId, sliceId);

        try {
            // 更新状态为 PROCESSING
            task.setStatus("PROCESSING");
            exportTaskMapper.updateById(task);

            // Python 只能消费本地路径：物化 bag（local 零拷贝 / minio 下载缓存）
            String localBag = storageAccess.materializeLocal(bagFilePath).toString();

            // 构建输出路径（minio 模式：staging 临时目录，成功后上传对象存储）
            String outputKey = StorageKeys.export(sliceId, exportId, "output.bag");
            Path outputPath = storageAccess.isMinioMode()
                    ? storageAccess.stageDir("exports").resolve(sliceId).resolve(exportId).resolve("output.bag")
                    : storageAccess.resolveLocalPath(outputKey);

            // 调用 Python extract_export.py
            Map<String, Object> args = new HashMap<>();
            args.put("bag_path", localBag);
            args.put("output_path", outputPath.toString());
            args.put("start_ts", request.getStartTs());
            args.put("end_ts", request.getEndTs());
            args.put("topics", request.getTopics());
            args.put("format", request.getFormat() != null ? request.getFormat() : "bag");

            String result = pythonExecutor.execute("extract_export.py", args, 600);

            // 解析 Python 输出
            Map<String, Object> resultMap = objectMapper.readValue(result, Map.class);

            boolean success = Boolean.TRUE.equals(resultMap.get("success"));
            if (!success) {
                throw new RuntimeException("Export extraction failed: " + result);
            }

            // minio 模式：staging 产物上传对象存储，filePath 存相对 key
            String filePath;
            if (storageAccess.isMinioMode()) {
                storageAccess.put(outputKey, outputPath, "application/octet-stream");
                filePath = outputKey;
            } else {
                filePath = outputPath.toString();
            }

            // 更新任务完成状态
            task.setStatus("READY");
            task.setFilePath(filePath);
            Object fileSize = resultMap.get("file_size");
            if (fileSize instanceof Number) {
                task.setFileSize(((Number) fileSize).longValue());
            }
            task.setExpiresAt(LocalDateTime.now().plusHours(24));
            exportTaskMapper.updateById(task);

            log.info("Export pipeline completed: exportId={}, filePath={}, fileSize={}",
                    exportId, filePath, task.getFileSize());

        } catch (Exception e) {
            log.error("Export pipeline FAILED: exportId={}", exportId, e);

            task.setStatus("ERROR");
            exportTaskMapper.updateById(task);
        }
    }
}
