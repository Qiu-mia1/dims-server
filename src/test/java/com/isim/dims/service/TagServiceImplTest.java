package com.isim.dims.service;

import com.isim.dims.dto.SliceTagResponse;
import com.isim.dims.entity.DataSliceTag;
import com.isim.dims.mapper.DataSliceTagMapper;
import com.isim.dims.service.impl.TagServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片打标 Service 单测。
 *
 * <p>职责已收窄为 {@code data_slice_tag} 关联表读写：标签定义全在 SIMS，
 * 本地无 tag 表（V29 移除），标签名由 {@link SimsTagCatalog} 解析。
 */
@ExtendWith(MockitoExtension.class)
class TagServiceImplTest {

    @Mock
    private DataSliceTagMapper dataSliceTagMapper;

    @Mock
    private SimsTagCatalog simsTagCatalog;

    @InjectMocks
    private TagServiceImpl tagService;

    // ============ getSliceTags ============

    @Test
    void getSliceTags_injectsNamesFromCatalog() {
        SliceTagResponse row = new SliceTagResponse();
        row.setId("tag-1");
        row.setType("manual");
        row.setTimestamp("2026-09-23T05:00:00Z");
        when(dataSliceTagMapper.selectSliceTagResponseBySliceId("slice-1")).thenReturn(List.of(row));
        when(simsTagCatalog.nameOf("tag-1")).thenReturn("highway");

        List<SliceTagResponse> result = tagService.getSliceTags("slice-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("highway");
        assertThat(result.get(0).getType()).isEqualTo("manual");
    }

    /**
     * 关联行指向的标签已在 SIMS 侧删除（或为 V20 遗留的场景域标签）时，
     * 必须给出占位名而不是抛异常 —— 一条失效引用不该让整个切片详情页报错。
     */
    @Test
    void getSliceTags_unresolvedTag_fallsBackToPlaceholder() {
        SliceTagResponse row = new SliceTagResponse();
        row.setId("00000000-0000-0000-0000-000000009999");
        when(dataSliceTagMapper.selectSliceTagResponseBySliceId("slice-1")).thenReturn(List.of(row));
        when(simsTagCatalog.nameOf("00000000-0000-0000-0000-000000009999"))
                .thenReturn("未知标签(00000000)");

        List<SliceTagResponse> result = tagService.getSliceTags("slice-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("未知标签(00000000)");
    }

    @Test
    void getSliceTags_noTags_returnsEmptyList() {
        when(dataSliceTagMapper.selectSliceTagResponseBySliceId("slice-1")).thenReturn(List.of());

        assertThat(tagService.getSliceTags("slice-1")).isEmpty();
    }

    // ============ setSliceTags ============

    @Test
    void setSliceTags_overwrite_deletesOldAndInsertsNew() {
        when(dataSliceTagMapper.delete(any())).thenReturn(3);

        tagService.setSliceTags("slice-1", List.of("tag-1", "tag-2"), "manual");

        verify(dataSliceTagMapper).delete(any());
        verify(dataSliceTagMapper, times(2)).insert(any(DataSliceTag.class));
    }

    @Test
    void setSliceTags_emptyTagList_clearsAllWithoutInsert() {
        when(dataSliceTagMapper.delete(any())).thenReturn(2);

        tagService.setSliceTags("slice-1", List.of(), "manual");

        verify(dataSliceTagMapper).delete(any());
        verify(dataSliceTagMapper, times(0)).insert(any(DataSliceTag.class));
    }

    @Test
    void setSliceTags_nullSource_defaultsToManual() {
        when(dataSliceTagMapper.delete(any())).thenReturn(0);

        tagService.setSliceTags("slice-1", List.of("tag-1"), null);

        verify(dataSliceTagMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (DataSliceTag dst) -> "manual".equals(dst.getSource())));
    }
}
