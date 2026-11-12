package com.isim.dims.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * pgvector 向量数据库配置（独立于 DIMS 主库，见 application.yml 顶层 pgvector 块）
 *
 * <p>用于：
 * <ul>
 *   <li>检索端：动态数据源 spring.datasource.dynamic.datasource.pgvector 的占位符来源</li>
 *   <li>写入端：把同参数注入 extract_video_segment.py，由其将帧图 embedding 写入该库</li>
 * </ul></p>
 *
 * @feature: visualization
 */
@Component
//@ConfigurationProperties(prefix = "pgvector")
@Data
public class PgvectorConfig {

    private String host = "192.168.114.2";
    private int port = 31323;
    private String database = "dims_pgvector";
    private String username = "isim";
    private String password = "zdjs_2023";
}