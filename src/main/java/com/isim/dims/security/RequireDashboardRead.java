package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用户个性化配置方案读权限：导出方案为可分享的 JSON 文件
 *
 * <p>对应 IAMS 权限点 {@code dims:dashboard:read}（显示名「配置方案查看」，
 * IAMS Flyway V9 起；V7 建点时叫「驾驶舱查看」）。code 因历史原因沿用 dashboard 前缀，勿按字面理解。</p>
 *
 * <p>与 {@link RequireExportRead} 同理——导出是<b>把数据带出系统</b>的敏感操作，
 * 虽在语义上只读，仍需持有 {@code dims:dashboard:read} 才能执行。
 * 其余纯读取（列表 / 详情）当前不挂权限注解，与项目既有读接口口径一致。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.DASHBOARD_READ + "')")
public @interface RequireDashboardRead {
}
