package com.isim.dims.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isim.dims.entity.SensorStream;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 传感器流 Mapper
 *
 * 继承 MyBatis-Plus BaseMapper 提供基本 CRUD，
 * 额外提供 {@link #selectBySliceId(String)} 按切片 ID 查询流列表。
 * 复杂 SQL 实现在对应的 SensorStreamMapper.xml 中。
 * @feature: visualization
 */
@Mapper
public interface SensorStreamMapper extends BaseMapper<SensorStream> {

    /**
     * 按切片 ID 查询关联的传感器流列表，结果按 topic 排序
     *
     * @param sliceId 切片 ID
     * @return 传感器流列表
     */
    List<SensorStream> selectBySliceId(@Param("sliceId") String sliceId);

    /**
     * 统计切片的 GPS/IMU 相关传感器流数量
     *
     * <p>判定条件与 Python 解析器 {@code parse_rosbag.py} 的
     * {@code infer_sensor_category()} 分类规则对齐：
     * sensor_category='gnss_imu' 优先，topic 关键词（gnss/gps/imu/navsat/novatel/ins）兜底，
     * 覆盖历史数据中 sensor_category 为空/遗留分类的情况。</p>
     *
     * @param sliceId 切片 ID
     * @return GPS/IMU 相关传感器流数量，0 表示该切片无 GPS 能力
     */
    @Select("SELECT COUNT(*) FROM sensor_stream WHERE slice_id = #{sliceId} " +
            "AND (sensor_category = 'gnss_imu' " +
            "OR topic ILIKE '%gnss%' OR topic ILIKE '%gps%' " +
            "OR topic ILIKE '%imu%' OR topic ILIKE '%navsat%' OR topic ILIKE '%novatel%' " +
            "OR topic ILIKE '%/ins%')")
    long countGpsStreams(@Param("sliceId") String sliceId);
}
