package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.DashboardStatsTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 驾驶舱统计提取任务 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供 dashboard_stats_task 表的完整 CRUD。
 * 任务串行/重试检查通过 LambdaQueryWrapper 完成（见 CockpitStatsPipelineServiceImpl）。
 * @feature: dashboard
 */
@Mapper
public interface DashboardStatsTaskMapper extends BaseMapper<DashboardStatsTask> {
}
