package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.CfdiMeta;
import org.apache.ibatis.annotations.Mapper;

/**
 * CFDI 元数据 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 cfdi_meta 表的完整 CRUD。
 * @feature: cfdi
 */
@Mapper
public interface CfdiMetaMapper extends BaseMapper<CfdiMeta> {
}
