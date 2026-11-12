package com.isim.dims.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 切片下载权限：原始 bag / OpenX(OpenSCENARIO) / CARLA / 日志产物下载，
 * 以及浏览器原生下载票据（{@code /downloads/rosbag/ticket}、{@code /downloads/log/ticket}）的签发。
 *
 * <p>数据外发属敏感操作，故从 {@code dims:export:read} 拆出单列，可单独收回。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + DimsPermission.SLICE_DOWNLOAD + "')")
public @interface RequireSliceDownload {
}
