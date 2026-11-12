package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.UserDashboardConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户个性化配置 Mapper（user_dashboard_config）
 *
 * <p>仅有按 user_id 单行读写的简单需求，BaseMapper 内置方法足够，不写 XML。</p>
 *
 * @feature: replay
 */
@Mapper
public interface UserDashboardConfigMapper extends BaseMapper<UserDashboardConfig> {
}
