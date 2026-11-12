package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.MxfOfflineNote;
import org.apache.ibatis.annotations.Mapper;

/**
 * MXF 积压车辆忽略名单 Mapper
 *
 * @feature: mxf
 */
@Mapper
public interface MxfOfflineNoteMapper extends BaseMapper<MxfOfflineNote> {
}
