package top.tankenqi.zingdb.backend.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import top.tankenqi.zingdb.backend.parser.Parser;
import top.tankenqi.zingdb.backend.parser.statement.Abort;
import top.tankenqi.zingdb.backend.parser.statement.Begin;
import top.tankenqi.zingdb.backend.parser.statement.Commit;
import top.tankenqi.zingdb.backend.parser.statement.Create;
import top.tankenqi.zingdb.backend.parser.statement.Delete;
import top.tankenqi.zingdb.backend.parser.statement.Desc;
import top.tankenqi.zingdb.backend.parser.statement.Drop;
import top.tankenqi.zingdb.backend.parser.statement.Insert;
import top.tankenqi.zingdb.backend.parser.statement.Select;
import top.tankenqi.zingdb.backend.parser.statement.Show;
import top.tankenqi.zingdb.backend.parser.statement.Stats;
import top.tankenqi.zingdb.backend.parser.statement.Update;
import top.tankenqi.zingdb.backend.tbm.BeginRes;
import top.tankenqi.zingdb.backend.tbm.TableManager;
import top.tankenqi.zingdb.common.Error;
import top.tankenqi.zingdb.common.ZingDBException;
import top.tankenqi.zingdb.transport.ColumnType;
import top.tankenqi.zingdb.transport.Package;
import top.tankenqi.zingdb.transport.ResultSet;

/**
 * 解析并执行一条 SQL，返回结构化 Package（OK / RESULT_SET / ERROR）。
 *
 * 每个 Executor 实例对应一个客户端连接，持有该连接当前的事务 xid。
 */
public class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);

    private long xid;
    final TableManager tbm;

    public Executor(TableManager tbm) {
        this.tbm = tbm;
        this.xid = 0;
    }

    /** 连接关闭时调用，若仍有未提交事务则 abort。 */
    public void close() {
        if (xid != 0) {
            log.warn("connection closed with in-flight transaction, aborting xid={}", xid);
            tbm.abort(xid);
            xid = 0;
        }
    }

    /**
     * 执行一条 SQL。返回的 Package 已设置 elapsedNanos。
     * 任何异常均被收敛成 Package.error(...)，调用方直接发送即可。
     */
    public Package execute(String sql) {
        long t0 = System.nanoTime();
        Package result;
        try {
            Object stat = Parser.Parse(sql.getBytes());
            if (stat instanceof Begin) {
                if (xid != 0) throw Error.NestedTransactionException;
                BeginRes r = tbm.begin((Begin) stat);
                xid = r.xid;
                result = Package.ok("begin", 0);
            } else if (stat instanceof Commit) {
                if (xid == 0) throw Error.NoTransactionException;
                tbm.commit(xid);
                xid = 0;
                result = Package.ok("commit", 0);
            } else if (stat instanceof Abort) {
                if (xid == 0) throw Error.NoTransactionException;
                tbm.abort(xid);
                xid = 0;
                result = Package.ok("abort", 0);
            } else {
                result = execDML(stat);
            }
        } catch (Exception e) {
            log.warn("execute failed: {}", sql, e);
            result = toErrorPackage(e);
        }
        long elapsed = System.nanoTime() - t0;
        result.setElapsedNanos(elapsed);
        // 慢查询 + 全局指标
        SlowQueryLogger.recordIfSlow(sql, elapsed);
        ServerMetrics.get().onQuery(elapsed, result.isError());
        return result;
    }

    private Package execDML(Object stat) throws Exception {
        boolean tmpTransaction = false;
        Exception suppressed = null;
        if (xid == 0) {
            tmpTransaction = true;
            BeginRes r = tbm.begin(new Begin());
            xid = r.xid;
        }
        try {
            if (stat instanceof Show) {
                ResultSet rs = tbm.showRS(xid);
                return Package.resultSet(rs);
            } else if (stat instanceof Stats) {
                return Package.resultSet(buildStats());
            } else if (stat instanceof Desc) {
                ResultSet rs = tbm.descRS(xid, ((Desc) stat).tableName);
                return Package.resultSet(rs);
            } else if (stat instanceof Create) {
                tbm.create(xid, (Create) stat);
                return Package.ok("create " + ((Create) stat).tableName, 0);
            } else if (stat instanceof Drop) {
                long n = tbm.drop(xid, (Drop) stat);
                return Package.ok("drop " + ((Drop) stat).tableName, n);
            } else if (stat instanceof Select) {
                ResultSet rs = tbm.readRS(xid, (Select) stat);
                return Package.resultSet(rs);
            } else if (stat instanceof Insert) {
                tbm.insert(xid, (Insert) stat);
                return Package.ok("insert 1", 1);
            } else if (stat instanceof Delete) {
                byte[] msg = tbm.delete(xid, (Delete) stat);
                long n = parseTrailingCount(msg, "delete ");
                return Package.ok(new String(msg), n);
            } else if (stat instanceof Update) {
                byte[] msg = tbm.update(xid, (Update) stat);
                long n = parseTrailingCount(msg, "update ");
                return Package.ok(new String(msg), n);
            } else {
                throw Error.InvalidCommandException;
            }
        } catch (Exception e) {
            suppressed = e;
            throw e;
        } finally {
            if (tmpTransaction) {
                if (suppressed != null) {
                    tbm.abort(xid);
                } else {
                    tbm.commit(xid);
                }
                xid = 0;
            }
        }
    }

    private static long parseTrailingCount(byte[] msg, String prefix) {
        String s = new String(msg);
        if (s.startsWith(prefix)) {
            try { return Long.parseLong(s.substring(prefix.length()).trim()); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static Package toErrorPackage(Exception e) {
        if (e instanceof ZingDBException) {
            ZingDBException z = (ZingDBException) e;
            return Package.error(z.getCode(), z.getMessage());
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return Package.error("SV-9999", msg);
    }

    /** 当前连接是否处于活动事务中（供服务端日志/客户端提示符用）。 */
    public boolean inTransaction() {
        return xid != 0;
    }

    /**
     * 构造 SHOW STATS 结果集：两列 metric / value，按固定顺序输出，方便客户端 grep。
     * 故意全部用 String value：很多指标是带单位 / 浮点 / 比率的，统一字符串避免类型噪音。
     */
    private ResultSet buildStats() {
        ServerMetrics m = ServerMetrics.get();
        ResultSet rs = new ResultSet(
            new String[]{"metric", "value"},
            new byte[]{ColumnType.STRING, ColumnType.STRING});
        rs.addRow(new Object[]{"uptime",            formatDuration(m.uptimeNanos())});
        rs.addRow(new Object[]{"connections.active",String.valueOf(m.activeConnections.get())});
        rs.addRow(new Object[]{"connections.total", String.valueOf(m.totalConnections.get())});
        rs.addRow(new Object[]{"queries.total",     String.valueOf(m.totalQueries.get())});
        rs.addRow(new Object[]{"queries.errors",    String.valueOf(m.totalErrors.get())});
        rs.addRow(new Object[]{"queries.slow",      String.valueOf(m.slowQueries.get())});
        rs.addRow(new Object[]{"queries.avg_ms",    String.format("%.3f", m.avgQueryMs())});
        rs.addRow(new Object[]{"slow_threshold_ms", String.valueOf(SlowQueryLogger.getThresholdMs())});
        rs.addRow(new Object[]{"tables",            String.valueOf(tbm.tableCount())});
        return rs;
    }

    private static String formatDuration(long nanos) {
        long s = nanos / 1_000_000_000L;
        long h = s / 3600;
        long mm = (s % 3600) / 60;
        long ss = s % 60;
        if (h > 0)  return String.format("%dh %dm %ds", h, mm, ss);
        if (mm > 0) return String.format("%dm %ds", mm, ss);
        return ss + "s";
    }
}
