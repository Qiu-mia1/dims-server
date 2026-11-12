package com.isim.dims.service.log;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 日志文件的读取抽象：屏蔽「本地文件系统」与「NFS 直连」两种来源。
 *
 * <p>为什么需要这一层：{@code data_slice.import_source_dir} 记的是<b>服务器/容器视角</b>的路径
 * （{@code /data/downloads/...}），而运行进程看到的文件系统未必有它 —— pod 可能没挂 NFS，
 * 本地调试时 {@code /data} 还会被解析成 {@code D:\data}。所以同一段「找日志 → 打包/直出」
 * 的逻辑要能跑在两种来源上，由 {@code dims.download.log.source} 决定用哪个。
 *
 * <p>路径约定：所有 {@code path} 参数与返回值都是<b>同一 namespace 下的绝对路径</b> ——
 * 本地模式是操作系统路径（Linux 下 {@code /data/...}，Windows 下 {@code D:\data\...}），
 * NFS 模式是导出根之下的路径（{@code /downloads/...}）。调用方不要跨 namespace 混用。
 *
 * <p>实现必须<b>不抛异常地降级</b>：{@link #isDirectory} / {@link #list} / {@link #size}
 * 遇到权限不足、连接失败、条目在扫描途中被删等情况，返回 false / 空表 / 0L 并打日志，
 * 因为逐条失败不代表整体失败 —— 调用方要的是「尽可能列出能读的」。
 * 只有 {@link #open} 允许抛 IOException（下载阶段读不到就得报错）。
 */
public interface LogFileStore {

    /** 存储类型标识，写进错误消息与取票响应，便于判断「这次读的是哪一边」。 */
    String type();

    /** 人类可读的来源标签（如 {@code nfs://10.130.212.160:/nfs/dims}），用于日志与报错。 */
    String label();

    /** 是否为目录；不存在、无权限、连接失败一律 false。 */
    boolean isDirectory(String path);

    /** 路径是否存在（文件或目录）；失败/无权限返回 false。 */
    boolean exists(String path);

    /** 子项完整路径（含目录），无权限或失败返回空表。 */
    List<String> list(String path);

    /** 文件字节数；取不到返回 0（调用方只用于体积上限判断，不用于精确校验）。 */
    long size(String path);

    /** 打开文件流读取内容（必须支持流式，不要把整个文件读进内存）。 */
    InputStream open(String path) throws IOException;

    /** 相对 {@code root} 的路径，统一用 {@code /} 分隔，作为 ZIP 条目名。 */
    static String relativeTo(String root, String path) {
        String r = root == null ? "" : root.replace('\\', '/');
        String p = path == null ? "" : path.replace('\\', '/');
        if (!r.endsWith("/")) {
            r = r + "/";
        }
        return p.startsWith(r) ? p.substring(r.length()) : p;
    }

    /** 取路径的最后一段（文件名），比 {@code Path#getFileName} 更宽容（两种分隔符都认）。 */
    static String fileName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    /** 取父目录路径；已在根上则返回 null。 */
    static String parent(String path) {
        if (path == null) {
            return null;
        }
        String p = path.replace('\\', '/');
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return idx == 0 ? "/" : null;
        }
        return p.substring(0, idx);
    }
}
