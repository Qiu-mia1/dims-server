package com.isim.dims.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 回放时间工具（bag 时间戳 ⇄ RFC3339 ⇄ 纳秒）
 *
 * <p>跨边界时间约定（契约 v1.3.0 / 宪法第 7 节）：
 * <ul>
 *   <li>请求/响应时间一律 UTC RFC3339，可含小数秒（如 {@code 2026-07-24T10:00:00.200Z}）</li>
 *   <li>内部消息索引用纳秒整数（unit: ns）</li>
 *   <li>消息体内 header.stamp / sensor_timestamp_us 为上电相对时间，禁止用于同步</li>
 * </ul>
 * </p>
 * @feature: infra, replay
 */
public final class ReplayTimeUtil {

    private ReplayTimeUtil() {
    }

    /**
     * RFC3339 字符串（可含小数秒）→ bag 时间戳纳秒
     *
     * @throws IllegalArgumentException 格式非法时抛出（由调用方映射为 400）
     */
    public static long parseNs(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            throw new IllegalArgumentException("time must not be blank");
        }
        Instant instant = Instant.parse(rfc3339.trim());
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    /**
     * bag 时间戳纳秒 → RFC3339 字符串（ISO_INSTANT，小数秒按实际精度输出）
     */
    public static String formatNs(long ns) {
        long seconds = Math.floorDiv(ns, 1_000_000_000L);
        int nanos = (int) Math.floorMod(ns, 1_000_000_000L);
        return Instant.ofEpochSecond(seconds, nanos).toString();
    }

    /**
     * 纳秒 → LocalDateTime（UTC，毫秒精度，与 DATETIME(3) 列对齐）
     */
    public static LocalDateTime toLocalDateTime(long ns) {
        long ms = Math.floorDiv(ns, 1_000_000L);
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    /**
     * RFC3339 字符串 → LocalDateTime（UTC，毫秒精度）
     *
     * @throws IllegalArgumentException 格式非法时抛出
     */
    public static LocalDateTime parseLocalDateTime(String rfc3339) {
        return toLocalDateTime(parseNs(rfc3339));
    }

    /**
     * LocalDateTime（UTC）→ RFC3339 字符串（毫秒精度）
     */
    public static String formatLocalDateTime(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        Instant instant = ldt.toInstant(ZoneOffset.UTC);
        long ms = instant.toEpochMilli();
        return formatNs(ms * 1_000_000L);
    }

    /**
     * 时间等值比较（毫秒精度，与 DATETIME(3) 存储精度对齐）
     */
    public static boolean timeEquals(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return toMillis(a) == toMillis(b);
    }

    private static long toMillis(LocalDateTime ldt) {
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
