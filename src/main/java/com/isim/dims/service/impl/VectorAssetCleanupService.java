package com.isim.dims.service.impl;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.isim.dims.mapper.ImageAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * pgvector 向量库（image_asset / video_clip_asset）关联数据清理
 *
 * <p>切片删除时向量库里的帧级/片段级 embedding 会变成孤儿数据：切片主库记录已删，
 * 检索端仍可能把它们召回，点开即 404。故删除切片需级联清理向量库。</p>
 *
 * <h3>为什么单独一个 Bean</h3>
 * <p>切片删除 {@code DataSliceServiceImpl#delete} 标注了 {@code @Transactional}，
 * 事务开启时连接已从 master 绑定；dynamic-datasource 的 {@code @DS} 在已有事务内
 * 不再切换数据源，直接调用 {@link ImageAssetMapper} 会把 pgvector 的表名打到 master 库。
 * 因此这里用 {@code propagation = NOT_SUPPORTED} 挂起外层主库事务，
 * 让 {@code @DS("pgvector")} 生效后再执行删除。</p>
 *
 * @feature: slice
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorAssetCleanupService {

    private final ImageAssetMapper imageAssetMapper;

    /**
     * 删除某切片在向量库中的全部关联数据（image_asset 帧级 + video_clip_asset 片段级）
     *
     * <p>video_clip_asset 为可选建表（未建时检索端降级为仅帧级召回），
     * 其删除失败只记日志不影响 image_asset 的清理结果。</p>
     *
     * @param sliceId 切片 ID
     * @return 删除行数（image_asset + video_clip_asset）
     */
    @DS("pgvector")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int deleteBySliceId(String sliceId) {
        int imageRows;
        try {
            imageRows = imageAssetMapper.deleteBySliceId(sliceId);
        } catch (Exception e) {
            log.warn("Failed to delete image_asset for sliceId={}", sliceId, e);
            throw e;
        }

        int clipRows = 0;
        try {
            clipRows = imageAssetMapper.deleteClipAssetBySliceId(sliceId);
        } catch (Exception e) {
            log.warn("Failed to delete video_clip_asset for sliceId={} (table may not exist)", sliceId, e);
        }

        log.info("Deleted vector assets: sliceId={}, image_asset={}, video_clip_asset={}",
                sliceId, imageRows, clipRows);
        return imageRows + clipRows;
    }
}
