package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isim.dims.dto.SliceTagResponse;
import com.isim.dims.entity.DataSliceTag;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.service.SimsTagCatalog;
import com.isim.dims.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 切片打标 Service 实现。
 *
 * <p>只负责 {@code data_slice_tag} 关联表的读写；标签名从 {@link SimsTagCatalog} 解析
 * （本地 {@code tag} 表已随 V29 移除）。
 *
 * @feature: tag
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final DataSliceTagMapper dataSliceTagMapper;
    private final SimsTagCatalog simsTagCatalog;

    @Override
    public List<SliceTagResponse> getSliceTags(String sliceId) {
        List<SliceTagResponse> rows = dataSliceTagMapper.selectSliceTagResponseBySliceId(sliceId);
        for (SliceTagResponse row : rows) {
            // 外键已随 tag 表移除：关联行可能指向 SIMS 侧已删除的标签 /
            // V20 遗留的场景域标签 → 解析不到时给占位名，绝不让一条失效引用打断整个列表
            row.setName(simsTagCatalog.nameOf(row.getId()));
        }
        return rows;
    }

    @Override
    @Transactional
    public void setSliceTags(String sliceId, List<String> tagIds, String source) {
        // 1. 删除切片上所有已有关联
        LambdaQueryWrapper<DataSliceTag> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(DataSliceTag::getSliceId, sliceId);
        int deletedCount = dataSliceTagMapper.delete(deleteWrapper);
        log.debug("Removed {} existing tag associations for sliceId={}", deletedCount, sliceId);

        // 2. 建立新的关联
        if (tagIds != null && !tagIds.isEmpty()) {
            for (String tagId : tagIds) {
                DataSliceTag dst = new DataSliceTag();
                dst.setSliceId(sliceId);
                dst.setTagId(tagId);
                dst.setSource(source != null ? source : "manual");
                dataSliceTagMapper.insert(dst);
            }
            log.info("Tags set for sliceId={}: {} tags (source={})", sliceId, tagIds.size(), source);
        } else {
            log.info("Tags cleared for sliceId={}", sliceId);
        }
    }
}
