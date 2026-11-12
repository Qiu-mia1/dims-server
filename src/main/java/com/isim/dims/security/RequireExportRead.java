package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 导出读操作权限：下载导出文件（数据泄露敏感操作，第一轮即强制）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.EXPORT_READ + "')")
public @interface RequireExportRead {
}
