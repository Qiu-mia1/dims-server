package com.isim.dims.service.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code data_slice.import_source_dir} 的路径值对象：以 {@code /} 分隔的段列表为载体。
 *
 * <p>为什么不直接用 {@link java.nio.file.Path}：库表里记的是<b>服务器视角</b>的 POSIX 路径
 * （{@code /data/downloads/mmt_test/<md5>/<session>}），而同一份值要同时喂给两种来源 ——
 * 本地文件系统（在 Windows 开发机上 {@code /data} 会变成 {@code D:\data}，段语义被盘符污染）
 * 和 NFS（需要的是去前缀后的 {@code /downloads/...}）。用段列表做「去日期段/补日期段」这类
 * 改写，两种目标各自渲染，逻辑只有一份。
 *
 * <p>构造时即校验：拒绝 {@code .} / {@code ..} 段（虽然值来自自家库表，但拼路径前挡一下是
 * 廉价且必要的防御），拒绝空值。
 */
public final class LogPath {

    private static final Pattern DATE_SEGMENT = Pattern.compile("\\d{8}");

    private final List<String> segments;

    private LogPath(List<String> segments) {
        this.segments = List.copyOf(segments);
    }

    /**
     * 解析库表值。
     *
     * @throws IllegalArgumentException 值为空、含 {@code .}/{@code ..} 段
     */
    public static LogPath parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is empty");
        }
        List<String> segments = new ArrayList<>();
        for (String part : raw.replace('\\', '/').split("/")) {
            String seg = part.trim();
            if (seg.isEmpty()) {
                continue;
            }
            if (".".equals(seg) || "..".equals(seg)) {
                throw new IllegalArgumentException("path contains relative segment: " + raw);
            }
            segments.add(seg);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("path has no segment: " + raw);
        }
        return new LogPath(segments);
    }

    /** POSIX 形式（前导 {@code /}），用于配置比对、错误消息、NFS 路径渲染。 */
    public String posix() {
        return "/" + String.join("/", segments);
    }

    /** 最后一段（会话目录名）。 */
    public String fileName() {
        return segments.get(segments.size() - 1);
    }

    /** 父目录；已到根则返回 null。 */
    public LogPath parent() {
        if (segments.size() <= 1) {
            return null;
        }
        return new LogPath(segments.subList(0, segments.size() - 1));
    }

    /** 是否以 {@code prefix}（POSIX 形式）为前缀；段的整段比对，避免 {@code /data2} 误命中 {@code /data}。 */
    public boolean startsWith(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        List<String> target = new ArrayList<>();
        for (String part : prefix.replace('\\', '/').split("/")) {
            if (!part.isBlank()) {
                target.add(part.trim());
            }
        }
        if (target.isEmpty() || target.size() > segments.size()) {
            return false;
        }
        for (int i = 0; i < target.size(); i++) {
            if (!segments.get(i).equals(target.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 去掉前缀后的 POSIX 路径（如 {@code /data/downloads/x} 去 {@code /data} → {@code /downloads/x}）。
     * 不以前缀开头时返回 null —— 调用方应据此跳过该来源，而不是猜一个路径出来。
     */
    public String stripPrefix(String prefix) {
        if (!startsWith(prefix)) {
            return null;
        }
        int count = 0;
        for (String part : prefix.replace('\\', '/').split("/")) {
            if (!part.isBlank()) {
                count++;
            }
        }
        if (count >= segments.size()) {
            return "/";
        }
        return "/" + String.join("/", segments.subList(count, segments.size()));
    }

    /** 去掉第一个形如 {@code yyyyMMdd} 的段；没有则返回原值。 */
    public LogPath dropDateSegment() {
        for (int i = 0; i < segments.size(); i++) {
            if (DATE_SEGMENT.matcher(segments.get(i)).matches()) {
                List<String> copy = new ArrayList<>(segments);
                copy.remove(i);
                return new LogPath(copy);
            }
        }
        return this;
    }

    /**
     * 在 {@code importMd5} 段之前插回日期段（{@code .../mmt_test/<yyyyMMdd>/<md5>/<session>}）。
     *
     * <p>锚点取 md5 段；库表没记 md5 时退化为「倒数第二段」（会话目录固定是最后一段）。
     * 已有日期段、日期为空、锚点落在根上 —— 任一情况返回原值。
     */
    public LogPath insertDateSegment(String date, String importMd5) {
        if (date == null || date.isBlank()) {
            return this;
        }
        int anchor = -1;
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            if (DATE_SEGMENT.matcher(seg).matches()) {
                return this;
            }
            if (anchor < 0 && importMd5 != null && !importMd5.isBlank()
                    && seg.equalsIgnoreCase(importMd5.trim())) {
                anchor = i;
            }
        }
        if (anchor < 0) {
            anchor = segments.size() - 2;
        }
        if (anchor <= 0) {
            return this;
        }
        List<String> copy = new ArrayList<>(segments);
        copy.add(anchor, date);
        return new LogPath(copy);
    }

    /**
     * {@code session_id} 形如 {@code ea236d24_20260831001436109984_public}，第二段头部 8 位即日期。
     * 取不到返回 null。
     */
    public static String dateFromSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        for (String part : sessionId.split("_")) {
            if (part.length() >= 8 && DATE_SEGMENT.matcher(part.substring(0, 8)).matches()) {
                return part.substring(0, 8);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return posix();
    }
}
