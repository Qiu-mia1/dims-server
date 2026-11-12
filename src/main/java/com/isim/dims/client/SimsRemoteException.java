package com.isim.dims.client;

/**
 * SIMS 远端调用失败。
 *
 * <p>DIMS 是标签的唯一入口（标签定义全在 SIMS），SIMS 的失败必须能在 DIMS 侧
 * 被区分出来并转成合适的 HTTP 语义：
 * <ul>
 *   <li>4xx：原样保留状态码与 SIMS 的错误文案（如删除时缺 {@code sims:tag:delete} 的 403、
 *       分类下仍有子节点的 400）——文案用 SIMS 原文，不自己编，避免用户按错误方向排查；</li>
 *   <li>5xx / 连接失败：归一为 <b>502</b>（DIMS 是代理，上游故障即 Bad Gateway）。</li>
 * </ul>
 *
 * @feature: tag
 */
public class SimsRemoteException extends RuntimeException {

    /** 归一后的 HTTP 状态码（调用方直接用作响应码）。 */
    private final int status;

    public SimsRemoteException(int status, String message) {
        super(message);
        this.status = status;
    }

    public SimsRemoteException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
