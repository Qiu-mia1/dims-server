package com.isim.dims.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果（对齐 SIMS {@code PageResult} 的 {@code {items,total}} 形状）。
 *
 * @param <T> 列表元素类型
 * @feature: tag
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> items;

    private long total;
}
