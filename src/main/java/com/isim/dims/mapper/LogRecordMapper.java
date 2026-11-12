package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.LogRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 日志包记录 Mapper
 *
 * @feature: ingestion
 */
@Mapper
public interface LogRecordMapper extends BaseMapper<LogRecord> {
}
