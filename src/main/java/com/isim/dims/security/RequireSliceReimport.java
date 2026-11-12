package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 切片重新导入权限：POST /v1/data-slices/reimport
 *
 * <p>该接口实现为「物理删除原切片（级联清理文件与记录）+ 重新目录导入」，
 * 破坏性等同于删除，因此**不归 {@link RequireSliceWrite}**，单列权限点，
 * 避免普通账户借「重新导入」绕过删除限制。</p>
 *
 * @feature: infra
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.SLICE_REIMPORT + "')")
public @interface RequireSliceReimport {
}
