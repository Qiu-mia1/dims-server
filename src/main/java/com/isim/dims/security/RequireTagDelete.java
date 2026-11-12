package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标签删除权限：DELETE /v1/tags/{tagId}
 *
 * <p>独立于 {@link RequireTagWrite}（新建/改名/打标）——删除标签会自动解绑
 * 所有切片关联，需可被单独授予或收回，见 IAMS Flyway
 * {@code V8__add_dims_delete_permissions.sql}。</p>
 *
 * @feature: infra
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.TAG_DELETE + "')")
public @interface RequireTagDelete {
}
