package com.isim.dims.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 *
 * 注册 MyBatis-Plus 插件拦截器骨架。
 * MyBatis-Plus 3.5.9 移除了 {@code PaginationInnerInterceptor}，
 * {@link MybatisPlusInterceptor} 通过 Spring Boot 自动装配收集容器中
 * 所有 {@code InnerInterceptor} Bean，无需在此显式注册。
 *
 * 分页计数逻辑在 {@link com.isim.dims.service.impl.DashboardServiceImpl#getRecentUploads}
 * 中通过手动 {@code selectCount + selectList + last(LIMIT)} 实现。
 * @feature: infra
 */
@Configuration
public class MybatisPlusConfig {

}
