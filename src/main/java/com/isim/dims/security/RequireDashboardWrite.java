package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用户个性化配置方案写权限：新建 / 编辑 / 删除 / 导入方案
 *
 * <p>对应 IAMS 权限点 {@code dims:dashboard:write}（显示名「配置方案管理」，
 * IAMS Flyway V9 起；V7 建点时叫「驾驶舱统计提取」）。code 因历史原因沿用 dashboard 前缀，勿按字面理解。</p>
 *
 * <p>遗留：{@code POST /v1/cockpit-stats/slices/{sliceId}/trigger}（驾驶舱统计提取，
 * 开发/测试用）仍复用本权限点，故 IAMS 后台勾上它等于同时开放该接口。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.DASHBOARD_WRITE + "')")
public @interface RequireDashboardWrite {
}
