package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.MxfPacket;
import org.apache.ibatis.annotations.Mapper;

/**
 * MXF 数据包主表 Mapper
 *
 * @feature: mxf
 */
@Mapper
public interface MxfPacketMapper extends BaseMapper<MxfPacket> {
}
