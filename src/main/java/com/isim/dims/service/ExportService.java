package com.isim.dims.service;

import com.isim.dims.dto.ExportListResponse;
import com.isim.dims.dto.ExportRequest;
import com.isim.dims.dto.ExportResponse;

import java.util.List;

/**
 * 数据导出 Service 接口
 *
 * 负责按时间窗口和 Topic 列表裁剪 bag 文件，生成导出产物供下游下载。
 * 导出产物默认保留 24 小时，过期自动清理。
 * 相同参数的请求可复用已有导出产物。
 * @feature: export
 */
public interface ExportService {

    /**
     * 创建导出任务
     *
     * 验证切片存在且为 READY 状态，创建 ExportTask，触发异步 Python 裁剪导出。
     *
     * @param sliceId 切片 UUID
     * @param request 导出请求参数（时间窗口、Topic 列表、格式）
     * @return 导出任务详情响应
     * @throws RuntimeException 如果切片不存在、状态不为 READY、或参数无效
     */
    ExportResponse createExport(String sliceId, ExportRequest request);

    /**
     * 查询导出任务状态和下载链接
     *
     * @param exportId 导出任务 UUID
     * @return 导出任务详情响应
     * @throws RuntimeException 如果导出任务不存在
     */
    ExportResponse getStatus(String exportId);

    /**
     * 查询指定切片的所有导出任务
     *
     * @param sliceId 切片 UUID
     * @return 导出任务列表（按创建时间倒序）
     * @throws RuntimeException 如果切片不存在
     */
    List<ExportResponse> listBySliceId(String sliceId);

    /**
     * 全局导出任务列表（分页，按创建时间倒序）
     *
     * 支持 sliceId / status 可选过滤；sliceId 非空时先验证切片存在。
     * 返回项通过 data_slice 表补填 slice_name。
     *
     * @param sliceId  切片 UUID（可选，null 表示不过滤）
     * @param status   任务状态（可选，PENDING|PROCESSING|READY|ERROR|EXPIRED，null 表示不过滤）
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页导出任务列表
     * @throws RuntimeException 如果 sliceId 非空且切片不存在
     */
    ExportListResponse listExports(String sliceId, String status, int page, int pageSize);

    /**
     * 下载导出文件（流式，MinIO 迁移）
     *
     * <p>不再全量读入内存：返回 {@link DownloadContent}（流 + 大小），
     * Controller 以 StreamingResponseBody 逐块转发。</p>
     *
     * @param exportId 导出任务 UUID
     * @return 文件流 + 字节大小
     * @throws RuntimeException 如果导出任务不存在、状态不为 READY、或文件不存在
     */
    DownloadContent download(String exportId);

    /**
     * 导出文件下载内容（MinIO 迁移）
     *
     * @param stream 文件输入流（调用方负责关闭）
     * @param size   文件字节大小
     */
    record DownloadContent(java.io.InputStream stream, long size) {
    }

    /**
     * 获取导出文件原始文件名
     *
     * @param exportId 导出任务 UUID
     * @return 文件名（不含路径）
     * @throws RuntimeException 如果导出任务不存在或文件不存在
     */
    String getExportFileName(String exportId);
}
