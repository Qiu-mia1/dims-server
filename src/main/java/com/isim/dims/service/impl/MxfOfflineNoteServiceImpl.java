package com.isim.dims.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.isim.dims.entity.MxfOfflineNote;
import com.isim.dims.mapper.MxfOfflineNoteMapper;
import com.isim.dims.service.MxfOfflineNoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * MXF 积压车辆忽略名单实现。
 *
 * @feature: mxf
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MxfOfflineNoteServiceImpl implements MxfOfflineNoteService {

    /** 与 mxf_offline_note.note 的 DDL 长度一致 */
    private static final int MAX_NOTE_LENGTH = 500;

    private final MxfOfflineNoteMapper noteMapper;

    @Override
    public List<MxfOfflineNote> listAll() {
        List<MxfOfflineNote> rows = noteMapper.selectList(
                new QueryWrapper<MxfOfflineNote>().orderByDesc("updated_at"));
        return rows == null ? List.of() : rows;
    }

    @Override
    @Transactional
    public MxfOfflineNote saveNote(String vin, String note, String operator) {
        String key = requireVin(vin);
        String text = requireNote(note);

        MxfOfflineNote existing = noteMapper.selectOne(
                new QueryWrapper<MxfOfflineNote>().eq("vin", key).last("LIMIT 1"));
        if (existing == null) {
            MxfOfflineNote entity = new MxfOfflineNote();
            entity.setVin(key);
            entity.setNote(text);
            entity.setCreatedBy(operator);
            noteMapper.insert(entity);
            log.info("[MXF] 备注新增 vin={} by={}", key, operator);
            return entity;
        }

        existing.setNote(text);
        existing.setCreatedBy(operator);
        // 「先查后改」：MyMetaObjectHandler 的 strictUpdateFill 仅在字段为 null 时填充，
        // 不显式刷新会导致 updated_at 停留在旧值
        existing.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        noteMapper.updateById(existing);
        log.info("[MXF] 备注覆盖 vin={} by={}", key, operator);
        return existing;
    }

    @Override
    public boolean removeNote(String vin) {
        String key = requireVin(vin);
        int deleted = noteMapper.delete(new QueryWrapper<MxfOfflineNote>().eq("vin", key));
        log.info("[MXF] 备注撤销 vin={} deleted={}", key, deleted);
        return deleted > 0;
    }

    private static String requireVin(String vin) {
        if (vin == null || vin.isBlank()) {
            throw new IllegalArgumentException("VIN_REQUIRED: 车辆标识不能为空");
        }
        return vin.trim();
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("NOTE_REQUIRED: 备注内容不能为空");
        }
        String text = note.trim();
        if (text.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("NOTE_TOO_LONG: 备注长度不能超过 " + MAX_NOTE_LENGTH + " 字符");
        }
        return text;
    }
}
