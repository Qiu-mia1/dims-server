package com.isim.dims.service;

import java.util.Map;

/**
 * 数据切片「按需加载」Service
 *
 * <p>meta-only 导入的切片（status=META_READY）只含事件元信息与前视鱼眼预览；
 * 用户在数据浏览器点击「加载」时，由本服务按切片记录的 import_bos_key
 * 从 BOS 全量拉取会话文件到本地，并复用既有本地目录导入流水线
 * （转换 → 解析 → 打标 → READY），加载完成后即可正常回放。</p>
 *
 * <p>两阶段进度：下载阶段占整体 0-20，流水线阶段占 20-100（由 IngestionTask.progress 折算）。</p>
 *
 * @feature: slice
 */
public interface SliceLoadService {

    /**
     * 触发按需加载（幂等）。
     *
     * <p>状态机：META_READY --(触发)--> LOADING --(下载完成，复用导入)-->
     * UPLOADING → CONVERTING → … → READY / ERROR。</p>
     *
     * @param sliceId 切片 ID（须为 META_READY 且记录了 import_bos_key）
     * @return { sliceId: 切片 ID, taskId: 加载任务 ID（用于进度查询）,
     *         status: LOADING, estimatedSeconds: 预计耗时（秒）,
     *         totalBytes / downloadedBytes / remainingBytes: 数据量口径 }
     * @throws IllegalStateException 切片不存在 / 状态不允许 / 缺少 import_bos_key / 已在加载中
     */
    Map<String, Object> startLoad(String sliceId);

    /**
     * 查询加载进度（前端「加载数据中，预计剩余 xx」轮询用）。
     *
     * <p>以 data_slice.status 为主线：LOADING=下载/处理中，READY/WORN=已完成，
     * ERROR=失败；下载阶段的字节级进度取自内存进度表（服务重启后回退为状态口径）。</p>
     *
     * @param sliceId 切片 ID
     * @return { sliceId, status, stage(DOWNLOADING|PROCESSING|DONE|FAILED|NOT_STARTED),
     *         progress(0-100 综合), downloadedBytes, totalBytes, estimatedSeconds,
     *         elapsedSeconds, remainingSeconds, taskId, errorMsg }
     * @throws IllegalStateException 切片不存在
     */
    Map<String, Object> getLoadStatus(String sliceId);
}
