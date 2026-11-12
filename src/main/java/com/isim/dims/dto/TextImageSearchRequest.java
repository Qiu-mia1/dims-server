package com.isim.dims.dto;

import lombok.Data;

/**
 * 以文搜图请求
 * @feature: visualization
 */
@Data
public class TextImageSearchRequest {
    /** 检索文本，如 “出现红绿灯的路口” */
    private String text;
    /** 返回条数上限（默认 50） */
    private Integer limit;
}