package com.isim.dims.exception;

/**
 * 切片不存在异常（Review W1）
 *
 * 替代 Controller 层以字符串前缀（msg.startsWith("SLICE_NOT_FOUND:")）
 * 判定 404 的脆弱写法：Service 抛出类型化异常，Controller 通过类型捕获精确返回 404。
 *
 * 消息保持 "SLICE_NOT_FOUND: {sliceId}" 前缀格式，兼容既有的错误码约定与日志检索。
 * @feature: infra
 */
public class SliceNotFoundException extends RuntimeException {

    /**
     * @param sliceId 不存在的切片 UUID
     */
    public SliceNotFoundException(String sliceId) {
        super("SLICE_NOT_FOUND: " + sliceId);
    }
}
