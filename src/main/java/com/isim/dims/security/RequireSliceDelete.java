package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 切片删除权限：DELETE /v1/data-slices/{sliceId}
 *
 * <p>独立于 {@link RequireSliceWrite}（上传/目录导入）——删除是破坏性操作，
 * 需可被单独授予或收回，见 IAMS Flyway {@code V8__add_dims_delete_permissions.sql}。</p>
 *
 * @feature: infra
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.SLICE_DELETE + "')")
public @interface RequireSliceDelete {
}
