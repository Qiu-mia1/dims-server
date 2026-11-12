package com.isim.dims.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.isim.dims.entity.MxfOfflineNote;
import com.isim.dims.mapper.MxfOfflineNoteMapper;
import com.isim.dims.service.impl.MxfOfflineNoteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MXF 积压车辆忽略名单服务单测。
 *
 * <p>注意 MP 3.5.9 的 {@code BaseMapper.insert/updateById} 有 Collection 重载，
 * Mockito 匹配必须写成 {@code any(MxfOfflineNote.class)}，裸 {@code any()} 会因
 * 「对 insert 的引用不明确」编译失败。</p>
 *
 * @feature: mxf
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MxfOfflineNoteServiceImplTest {

    private static final String VIN = "31a877fbe5e199cdbbdad402687d7715";

    @Mock
    private MxfOfflineNoteMapper noteMapper;

    private MxfOfflineNoteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MxfOfflineNoteServiceImpl(noteMapper);
    }

    @Test
    @DisplayName("首次填写备注：insert 一条新记录，字段与 trim 后的备注正确")
    void 新增备注() {
        when(noteMapper.selectOne(any())).thenReturn(null);

        MxfOfflineNote saved = service.saveNote("  " + VIN + "  ", "  已返厂维修  ", "user-1");

        assertThat(saved.getVin()).isEqualTo(VIN);
        assertThat(saved.getNote()).isEqualTo("已返厂维修");
        assertThat(saved.getCreatedBy()).isEqualTo("user-1");

        ArgumentCaptor<MxfOfflineNote> captor = ArgumentCaptor.forClass(MxfOfflineNote.class);
        verify(noteMapper).insert(captor.capture());
        assertThat(captor.getValue().getNote()).isEqualTo("已返厂维修");
        verify(noteMapper, never()).updateById(any(MxfOfflineNote.class));
    }

    @Test
    @DisplayName("重复填写：覆盖旧备注（updateById），并显式刷新 updated_at")
    void 覆盖备注() {
        MxfOfflineNote existing = new MxfOfflineNote();
        existing.setId("n1");
        existing.setVin(VIN);
        existing.setNote("旧备注");
        existing.setUpdatedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        when(noteMapper.selectOne(any())).thenReturn(existing);

        MxfOfflineNote saved = service.saveNote(VIN, "新备注", "user-2");

        assertThat(saved.getId()).isEqualTo("n1");
        assertThat(saved.getNote()).isEqualTo("新备注");
        assertThat(saved.getCreatedBy()).isEqualTo("user-2");
        // MyMetaObjectHandler 只在字段为 null 时填充 → 必须显式刷新
        assertThat(saved.getUpdatedAt()).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
        verify(noteMapper).updateById(existing);
        verify(noteMapper, never()).insert(any(MxfOfflineNote.class));
    }

    @Test
    @DisplayName("撤销：删到记录返回 true，没删到返回 false（幂等）")
    void 撤销备注() {
        when(noteMapper.delete(any(Wrapper.class))).thenReturn(1);
        assertThat(service.removeNote(VIN)).isTrue();

        when(noteMapper.delete(any(Wrapper.class))).thenReturn(0);
        assertThat(service.removeNote(VIN)).isFalse();
    }

    @Test
    @DisplayName("备注为空 / 全空白 → IllegalArgumentException（不落库）")
    void 备注必填() {
        assertThatThrownBy(() -> service.saveNote(VIN, null, "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOTE_REQUIRED");
        assertThatThrownBy(() -> service.saveNote(VIN, "   ", "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOTE_REQUIRED");
        verify(noteMapper, never()).insert(any(MxfOfflineNote.class));
    }

    @Test
    @DisplayName("VIN 为空 → IllegalArgumentException")
    void vin必填() {
        assertThatThrownBy(() -> service.saveNote("  ", "备注", "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIN_REQUIRED");
        assertThatThrownBy(() -> service.removeNote(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIN_REQUIRED");
    }

    @Test
    @DisplayName("备注超 500 字符 → IllegalArgumentException")
    void 备注长度上限() {
        String tooLong = "备".repeat(501);
        assertThatThrownBy(() -> service.saveNote(VIN, tooLong, "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOTE_TOO_LONG");
    }

    @Test
    @DisplayName("listAll 按更新时间倒序透传，null 结果兜底为空列表")
    void 列表查询() {
        MxfOfflineNote a = new MxfOfflineNote();
        a.setVin("v1");
        when(noteMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(a)));
        assertThat(service.listAll()).hasSize(1);

        when(noteMapper.selectList(any())).thenReturn(null);
        assertThat(service.listAll()).isEmpty();
    }
}
