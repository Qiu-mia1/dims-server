package com.isim.dims.service;

import com.isim.dims.dto.DirectoryImportRequest;
import com.isim.dims.dto.DirectoryImportResponse;

/**
 * 本地目录导入 Service
 *
 * 扫描服务器本地目录下符合 MD5 命名的子目录（{md5_32hex}/{md5后8位}_{时间戳}_public），
 * 经 vin_md5_mapping 反查 VIN，批量创建 DataSlice + IngestionTask 记录，
 * 并委派 {@link LocalDirectoryImportPipelineService} 异步执行 CFDI 同款转换流水线。
 * @feature: ingestion
 */
public interface LocalDirectoryImportService {

    /**
     * 扫描并导入本地目录（同步建记录 + 异步派发转换流水线）
     *
     * 目录结构约定：
     * <pre>
     * {user_dir}/{project}/{date}/
     *   └── {md5_32hex}/                          ← 如 ff74ef8686a4c591241480264bfb23eb
     *       └── {md5后8位}_{时间戳}_public/        ← 如 4bfb23eb_20260709143844460937_public
     *           └── public/data/{mfbag, other}/
     * </pre>
     *
     * @param request 扫描目录绝对路径 + 命名前缀 + strict 模式
     * @return 导入摘要（jobId / imported / skipped），异步流水线随后执行
     * @throws RuntimeException 目录不可用（DIRECTORY_NOT_FOUND:）、
     *                          strict 模式下任一会话校验失败（MD5_NOT_MAPPED: / ALREADY_IMPORTED: / MFBAG_NOT_FOUND: / INVALID_SESSION:）
     */
    DirectoryImportResponse importDirectory(DirectoryImportRequest request);

    /**
     * meta-only 导入（MMT BOS 调度专用）
     *
     * <p>调度下载阶段只拉取 meta + 前视鱼眼 h265（见 DownloadMMTDataBOSSchedule 的
     * key 过滤），本方法扫描这些不完整的会话目录：仅解析 meta 建切片
     * （status=META_READY，含事件统计元信息），<b>不校验 mfbag、不创建 IngestionTask、
     * 不跑转换流水线</b>，并异步把前视鱼眼 h265 转码为可播放预览 mp4。</p>
     *
     * <p>切片记录 import_bos_key（= request.bosKeyPrefix/{sessionDir}），
     * 用户在数据浏览器点「加载」时由 SliceLoadService 按此前缀全量拉取并
     * 复用 {@link LocalDirectoryImportPipelineService} 完成完整导入。</p>
     *
     * @param request directory=本地子集目录，bosKeyPrefix=该目录对应的 BOS 对象前缀
     * @return 导入摘要（imported=META_READY 切片，skipped=无 meta 或已导入的会话）
     */
    DirectoryImportResponse importDirectoryMetaOnly(DirectoryImportRequest request);

    long countConcurrentTasks();
}
