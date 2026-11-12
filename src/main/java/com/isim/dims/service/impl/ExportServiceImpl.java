package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.dto.ExportListResponse;
import com.isim.dims.dto.ExportRequest;
import com.isim.dims.dto.ExportResponse;
import com.isim.dims.dto.ExportTaskSummary;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.entity.ExportTask;
import com.isim.dims.exception.SliceNotFoundException;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ExportTaskMapper;
import com.isim.dims.service.ExportPipelineService;
import com.isim.dims.service.ExportService;
import com.isim.dims.service.StorageStat;
import com.isim.dims.util.PythonExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 数据导出 Service 实现
 *
 * 核心业务逻辑：
 * 1. createExport：验证切片状态 → 创建任务 → 委派异步 Python 裁剪导出 → 返回任务信息
 * 2. getStatus：查询导出任务 → 转换为响应 DTO
 *
 * 异步流水线委派给 {@link ExportPipelineService} 独立 Bean，
 * 确保 @Async 通过 Spring AOP 代理正常生效。
 * @feature: export
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private final DataSliceMapper dataSliceMapper;
    private final ExportTaskMapper exportTaskMapper;
    private final PythonExecutor pythonExecutor;
    private final ExportPipelineService exportPipelineService;
    private final StorageAccess storageAccess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dims.storage.export-dir:/data/exports}")
    private String exportDir;

    @Value("${dims.storage.bag-dir:/data/bags}")
    private String bagDir;

    // ========================================================================
    // 创建导出任务
    // ========================================================================

    @Override
    @Transactional
    public ExportResponse createExport(String sliceId, ExportRequest request) {
        // 1. 验证切片存在且为 READY 状态
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new SliceNotFoundException(sliceId);
        }
        if (!"READY".equals(slice.getStatus())) {
            throw new RuntimeException("INVALID_SLICE_STATUS: slice status must be READY, current="
                    + slice.getStatus());
        }
        // 归档拦截（minio 模式 + storage_type=LOCAL → StorageArchivedException → 410）：
        // 归档切片立即拒绝导出（含缓存复用路径），不落导出任务
        storageAccess.assertNotArchived(slice);

        // 2. 检查是否已有相同参数的有效导出（缓存复用）
        ExportTask existingTask = findExistingValidExport(sliceId, request);
        if (existingTask != null) {
            log.info("Reusing existing export: exportId={}, sliceId={}", existingTask.getId(), sliceId);
            return toExportResponse(existingTask);
        }

        // 3. 创建导出任务记录
        String exportId = UUID.randomUUID().toString();
        ExportTask task = new ExportTask();
        task.setId(exportId);
        task.setSliceId(sliceId);
        task.setStartTs(parseTime(request.getStartTs()));
        task.setEndTs(parseTime(request.getEndTs()));
        task.setTopics(request.getTopics() != null && !request.getTopics().isEmpty()
                ? serializeTopics(request.getTopics()) : null);
        task.setFormat(request.getFormat() != null ? request.getFormat() : "bag");
        task.setStatus("PENDING");
        task.setExpiresAt(LocalDateTime.now().plusHours(24));
        exportTaskMapper.insert(task);

        // 4. 委派异步导出流水线（独立 Bean，@Async 通过 Spring AOP 正常生效）
        // 注意：传入 ExportTask 对象而非仅 ID，避免事务未提交时异步线程 selectById 返回 null 的竞态条件
        exportPipelineService.processExportPipeline(task, slice.getFilePath(), request);

        // 5. 返回导出任务信息
        log.info("Export created: exportId={}, sliceId={}, format={}", exportId, sliceId, request.getFormat());

        return toExportResponse(task);
    }

    // ========================================================================
    // 查询导出状态
    // ========================================================================

    @Override
    public ExportResponse getStatus(String exportId) {
        ExportTask task = exportTaskMapper.selectById(exportId);
        if (task == null) {
            throw new RuntimeException("EXPORT_NOT_FOUND: " + exportId);
        }
        return toExportResponse(task);
    }

    // ========================================================================
    // 查询切片导出列表
    // ========================================================================

    @Override
    public List<ExportResponse> listBySliceId(String sliceId) {
        // 1. 验证切片存在
        DataSlice slice = dataSliceMapper.selectById(sliceId);
        if (slice == null) {
            throw new SliceNotFoundException(sliceId);
        }

        // 2. 查询该切片的所有导出任务（按创建时间倒序）
        List<ExportTask> tasks = exportTaskMapper.selectList(
                new LambdaQueryWrapper<ExportTask>()
                        .eq(ExportTask::getSliceId, sliceId)
                        .orderByDesc(ExportTask::getCreatedAt)
        );

        // 3. 转换为响应 DTO
        return tasks.stream()
                .map(this::toExportResponse)
                .toList();
    }

    // ========================================================================
    // 全局导出任务列表（分页）
    // ========================================================================

    @Override
    public ExportListResponse listExports(String sliceId, String status, int page, int pageSize) {
        // 1. sliceId 非空时验证切片存在（不存在 → 404）
        if (sliceId != null && !sliceId.isBlank()) {
            if (dataSliceMapper.selectById(sliceId) == null) {
                throw new SliceNotFoundException(sliceId);
            }
        }

        // 2. 构建过滤条件：slice_id / status 可选过滤
        LambdaQueryWrapper<ExportTask> wrapper = new LambdaQueryWrapper<ExportTask>()
                .eq(sliceId != null && !sliceId.isBlank(), ExportTask::getSliceId, sliceId)
                .eq(status != null && !status.isBlank(), ExportTask::getStatus, status);

        // 3. 手动分页（MyBatis-Plus 3.5.9 移除了 PaginationInnerInterceptor，
        //    selectPage 的自动计数和 LIMIT 注入不可用，与 DashboardServiceImpl 保持一致）
        // 安全性：page / pageSize 来自 Controller 层，类型为 int，无法注入 SQL；
        //    last("LIMIT x OFFSET y") 为 MyBatis-Plus 的 SQL 尾部拼接，参数均为服务端生成的整数。
        // count 不能带 ORDER BY：PG 拒绝聚合查询对非聚合列排序（MySQL 容忍、PG 报错），
        //    排序（created_at DESC）只加到列表查询。
        long total = exportTaskMapper.selectCount(wrapper);
        int offset = (page - 1) * pageSize;
        List<ExportTask> tasks = exportTaskMapper.selectList(
                wrapper.clone().orderByDesc(ExportTask::getCreatedAt)
                        .last("LIMIT " + pageSize + " OFFSET " + offset));

        // 4. 批量补填 slice_name：一次性查 data_slice，避免 N+1 查询
        Map<String, String> sliceNameMap = buildSliceNameMap(tasks);

        // 5. 组装分页响应
        ExportListResponse resp = new ExportListResponse();
        resp.setItems(tasks.stream()
                .map(t -> toSummary(t, sliceNameMap.get(t.getSliceId())))
                .toList());
        resp.setTotal(total);
        resp.setPage(page);
        resp.setPageSize(pageSize);
        return resp;
    }

    /**
     * 批量查询切片名称映射（sliceId → name）
     */
    private Map<String, String> buildSliceNameMap(List<ExportTask> tasks) {
        Set<String> sliceIds = new HashSet<>();
        for (ExportTask t : tasks) {
            if (t.getSliceId() != null && !t.getSliceId().isBlank()) {
                sliceIds.add(t.getSliceId());
            }
        }
        Map<String, String> nameMap = new HashMap<>();
        if (!sliceIds.isEmpty()) {
            dataSliceMapper.selectBatchIds(sliceIds).stream()
                    .filter(s -> s.getId() != null)
                    .forEach(s -> nameMap.put(s.getId(), s.getName()));
        }
        return nameMap;
    }

    /**
     * ExportTask → ExportTaskSummary 转换（列表项）
     */
    private ExportTaskSummary toSummary(ExportTask task, String sliceName) {
        ExportTaskSummary summary = new ExportTaskSummary();
        summary.setExportId(task.getId());
        summary.setSliceId(task.getSliceId());
        summary.setSliceName(sliceName);
        summary.setStartTs(formatTime(task.getStartTs()));
        summary.setEndTs(formatTime(task.getEndTs()));
        summary.setFormat(task.getFormat());
        summary.setStatus(task.getStatus());
        summary.setFileSize(task.getFileSize());
        // W3：仅 READY 任务提供下载链接，其余状态（PENDING/PROCESSING/ERROR/EXPIRED）为 null
        summary.setDownloadUrl("READY".equals(task.getStatus())
                ? "/v1/exports/" + task.getId() + "/download" : null);
        summary.setExpiresAt(formatTime(task.getExpiresAt()));
        summary.setCreatedAt(formatTime(task.getCreatedAt()));
        summary.setUpdatedAt(formatTime(task.getUpdatedAt()));
        return summary;
    }

    // ========================================================================
    // 导出文件下载
    // ========================================================================

    @Override
    public DownloadContent download(String exportId) {
        ExportTask task = exportTaskMapper.selectById(exportId);
        if (task == null) {
            throw new RuntimeException("EXPORT_NOT_FOUND: " + exportId);
        }
        if (!"READY".equals(task.getStatus())) {
            throw new RuntimeException("EXPORT_NOT_READY: " + exportId + ", status=" + task.getStatus());
        }
        String filePath = task.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new RuntimeException("EXPORT_FILE_NOT_FOUND: " + exportId);
        }
        // 归档拦截：minio 模式下归档切片（storage_type=LOCAL）→ 410 DATA_ARCHIVED
        storageAccess.assertNotArchived(dataSliceMapper.selectById(task.getSliceId()));
        // 流式下载：不再全量读入内存（filePath 可为相对 key 或存量绝对路径）
        StorageStat stat = storageAccess.stat(filePath);
        return new DownloadContent(storageAccess.open(filePath), stat.size());
    }

    @Override
    public String getExportFileName(String exportId) {
        ExportTask task = exportTaskMapper.selectById(exportId);
        if (task == null) {
            throw new RuntimeException("EXPORT_NOT_FOUND: " + exportId);
        }
        String filePath = task.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new RuntimeException("EXPORT_FILE_NOT_FOUND: " + exportId);
        }
        return java.nio.file.Path.of(filePath).getFileName().toString();
    }

    // ========================================================================
    // 私有辅助方法
    // ========================================================================

    /**
     * ExportTask → ExportResponse 转换
     */
    private ExportResponse toExportResponse(ExportTask task) {
        ExportResponse resp = new ExportResponse();
        resp.setExportId(task.getId());
        resp.setSliceId(task.getSliceId());
        resp.setStatus(task.getStatus());
        resp.setFormat(task.getFormat());
        resp.setFileSize(task.getFileSize());
        // W3：仅 READY 任务提供下载链接，其余状态（PENDING/PROCESSING/ERROR/EXPIRED）为 null
        resp.setDownloadUrl("READY".equals(task.getStatus())
                ? "/v1/exports/" + task.getId() + "/download" : null);
        resp.setExpiresAt(formatTime(task.getExpiresAt()));
        resp.setCreatedAt(formatTime(task.getCreatedAt()));
        return resp;
    }

    /**
     * 查找相同参数的有效导出任务（仍有效期内）
     */
    private ExportTask findExistingValidExport(String sliceId, ExportRequest request) {
        LocalDateTime now = LocalDateTime.now();
        return exportTaskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExportTask>()
                        .eq(ExportTask::getSliceId, sliceId)
                        .eq(ExportTask::getStatus, "READY")
                        .eq(ExportTask::getFormat, request.getFormat() != null ? request.getFormat() : "bag")
                        .gt(ExportTask::getExpiresAt, now)
        ).stream()
                .filter(t -> paramsMatch(t, request))
                .findFirst()
                .orElse(null);
    }

    /**
     * 比对任务参数与请求参数是否一致
     */
    private boolean paramsMatch(ExportTask task, ExportRequest request) {
        if (!timeEquals(task.getStartTs(), parseTime(request.getStartTs()))
                || !timeEquals(task.getEndTs(), parseTime(request.getEndTs()))) {
            return false;
        }
        String taskFormat = task.getFormat() != null ? task.getFormat() : "bag";
        String reqFormat = request.getFormat() != null ? request.getFormat() : "bag";
        if (!taskFormat.equals(reqFormat)) {
            return false;
        }
        if (request.getTopics() == null || request.getTopics().isEmpty()) {
            return task.getTopics() == null || "[]".equals(task.getTopics());
        }
        try {
            java.util.List<String> taskTopics = objectMapper.readValue(
                    task.getTopics(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
            return taskTopics.equals(request.getTopics());
        } catch (Exception e) {
            log.warn("Failed to parse task topics JSON: {}", task.getTopics(), e);
            return false;
        }
    }

    /**
     * 时间字符串 → LocalDateTime 解析
     */
    private LocalDateTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        try {
            return LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception e2) {
                log.warn("Failed to parse time: {}", timeStr, e2);
                return null;
            }
        }
    }

    /**
     * Topic 列表 → JSON 字符串序列化
     */
    private String serializeTopics(java.util.List<String> topics) {
        if (topics == null || topics.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(topics);
        } catch (Exception e) {
            log.warn("Failed to serialize topics", e);
            return null;
        }
    }

    /**
     * LocalDateTime 比对（去除纳秒精度差异）
     */
    private boolean timeEquals(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.withNano(0).equals(b.withNano(0));
    }

    /**
     * LocalDateTime → RFC3339 字符串
     */
    private String formatTime(LocalDateTime dt) {
        if (dt == null) return null;
        return dt.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
