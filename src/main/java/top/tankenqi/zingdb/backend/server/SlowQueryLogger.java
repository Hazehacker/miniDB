package top.tankenqi.zingdb.backend.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 慢查询日志。
 *
 * 设计要点：
 *   - 单一全局阈值（毫秒），默认 200ms；可通过系统属性 zingdb.slow.ms 覆盖。
 *   - 仅记录 WARN 一行：`slow query (elapsed_ms) :: <sql>`，便于 grep。
 *   - SQL 过长时截断至 500 字符，避免巨型 SQL 撑爆日志。
 *   - 这里只关心阈值判定与输出，不做异步队列 / 采样等花活儿；
 *     交给 logback 的 appender 决定如何刷盘。
 */
public final class SlowQueryLogger {

    private static final Logger log = LoggerFactory.getLogger("slow-query");

    /** 默认 200ms。 */
    public static final long DEFAULT_THRESHOLD_MS = 200L;

    private static final int MAX_SQL_LEN = 500;

    private static volatile long thresholdNanos = readThresholdProp() * 1_000_000L;

    private SlowQueryLogger() {}

    public static void setThresholdMs(long ms) {
        thresholdNanos = Math.max(0L, ms) * 1_000_000L;
    }

    public static long getThresholdMs() {
        return thresholdNanos / 1_000_000L;
    }

    /** 由 Executor 在每条 SQL 执行后调用。 */
    public static void recordIfSlow(String sql, long elapsedNanos) {
        if (thresholdNanos <= 0 || elapsedNanos < thresholdNanos) return;
        String trimmed = sql == null ? "" : sql;
        if (trimmed.length() > MAX_SQL_LEN) {
            trimmed = trimmed.substring(0, MAX_SQL_LEN) + "…";
        }
        log.warn("slow query ({} ms) :: {}", elapsedNanos / 1_000_000L, trimmed);
    }

    private static long readThresholdProp() {
        String v = System.getProperty("zingdb.slow.ms");
        if (v == null || v.isEmpty()) return DEFAULT_THRESHOLD_MS;
        try { return Math.max(0L, Long.parseLong(v.trim())); }
        catch (NumberFormatException e) { return DEFAULT_THRESHOLD_MS; }
    }
}
