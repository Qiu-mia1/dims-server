package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MXF 看板读权限：KPI / 事件分布 / 特殊事件通知 / 数据包列表
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.MXF_READ + "')")
public @interface RequireMxfRead {
}
