package top.tankenqi.zingdb.backend.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务端运行指标采集（进程级单例）。
 *
 * 故意只用 AtomicLong：
 *   - 不引入 Micrometer / DropWizard 等重量级依赖；
 *   - 高频路径上 AtomicLong.add 的开销可以忽略；
 *   - 复杂的延迟分布（p95/p99）留到将来真有需要时再做。
 *
 * 字段语义：
 *   activeConnections   当前活动连接数（HandleSocket 进入循环时 +1，退出时 -1）
 *   totalConnections    历史累计连接数
 *   totalQueries        历史累计执行次数（不分成功 / 失败）
 *   totalErrors         其中失败的次数（Executor 返回 ERROR Package）
 *   slowQueries         其中触发慢查询日志阈值的次数
 *   totalElapsedNanos   所有查询累计耗时
 *   startNanos          进程启动时间，配合 now() - startNanos 计算 uptime
 */
public final class ServerMetrics {

    public final AtomicLong activeConnections = new AtomicLong();
    public final AtomicLong totalConnections  = new AtomicLong();
    public final AtomicLong totalQueries      = new AtomicLong();
    public final AtomicLong totalErrors       = new AtomicLong();
    public final AtomicLong slowQueries       = new AtomicLong();
    public final AtomicLong totalElapsedNanos = new AtomicLong();
    public final long startNanos = System.nanoTime();

    private static final ServerMetrics INSTANCE = new ServerMetrics();
    public static ServerMetrics get() { return INSTANCE; }

    private ServerMetrics() {}

    public void onConnect() {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    public void onDisconnect() {
        activeConnections.decrementAndGet();
    }

    public void onQuery(long elapsedNanos, boolean error) {
        totalQueries.incrementAndGet();
        totalElapsedNanos.addAndGet(elapsedNanos);
        if (error) totalErrors.incrementAndGet();
        if (elapsedNanos >= SlowQueryLogger.getThresholdMs() * 1_000_000L) {
            slowQueries.incrementAndGet();
        }
    }

    public long uptimeNanos() {
        return System.nanoTime() - startNanos;
    }

    /** 平均查询耗时（毫秒）。无样本时返回 0。 */
    public double avgQueryMs() {
        long q = totalQueries.get();
        if (q == 0) return 0;
        return totalElapsedNanos.get() / 1_000_000.0 / q;
    }
}
