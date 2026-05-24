package top.tankenqi.zingdb.backend.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import top.tankenqi.zingdb.backend.dm.DataManager;
import top.tankenqi.zingdb.backend.tbm.TableManager;
import top.tankenqi.zingdb.backend.tm.TransactionManager;
import top.tankenqi.zingdb.backend.vm.VersionManager;
import top.tankenqi.zingdb.transport.Package;
import top.tankenqi.zingdb.transport.ResultSet;

/**
 * SHOW STATS 端到端测试 + ServerMetrics 计数器。
 *
 * 与 EndToEndSqlTest 同样的临时 DB 模式；这里关心的是 stats 输出形状是否稳定，
 * 以及连续若干次执行后 queries.total 是否正确递增。
 */
public class StatsTest {

    private String dir;
    private String path;

    @Before
    public void setUp() throws Exception {
        File tmp = Files.createTempDirectory("zingdb-stats-").toFile();
        dir = tmp.getAbsolutePath();
        path = dir + "/db";
        TransactionManager tm = TransactionManager.create(path);
        DataManager dm = DataManager.create(path, 1L << 20, tm);
        VersionManager vm = VersionManager.newVersionManager(tm, dm);
        TableManager.create(path, vm, dm);
        tm.close();
        dm.close();
    }

    @After
    public void tearDown() {
        File d = new File(dir);
        if (d.isDirectory()) {
            for (File f : d.listFiles()) f.delete();
            d.delete();
        }
    }

    private Executor open() {
        TransactionManager tm = TransactionManager.open(path);
        DataManager dm = DataManager.open(path, 1L << 20, tm);
        VersionManager vm = VersionManager.newVersionManager(tm, dm);
        TableManager tbm = TableManager.open(path, vm, dm);
        return new Executor(tbm);
    }

    @Test
    public void showStatsReturnsExpectedMetrics() {
        Executor exe = open();
        // 先执行几条 SQL 增加计数
        long before = ServerMetrics.get().totalQueries.get();
        exe.execute("create table t a int32, (index a)");
        exe.execute("insert into t values (1)");
        exe.execute("insert into t values (2)");
        exe.execute("select * from t where a > 0");

        Package p = exe.execute("show stats");
        assertTrue("stats must be RESULT_SET, got " + p.getType(), p.isResultSet());
        ResultSet rs = p.getResultSet();
        assertEquals(2, rs.columnCount());
        assertEquals("metric", rs.getColumnNames()[0]);
        assertEquals("value",  rs.getColumnNames()[1]);

        // 必须包含这些固定指标行
        java.util.Set<String> metrics = new java.util.HashSet<>();
        for (Object[] row : rs.getRows()) metrics.add((String) row[0]);
        for (String key : new String[]{
            "uptime", "connections.active", "connections.total",
            "queries.total", "queries.errors", "queries.slow",
            "queries.avg_ms", "slow_threshold_ms", "tables"
        }) {
            assertTrue("missing metric: " + key, metrics.contains(key));
        }

        // queries.total 至少递增到 before + 5（4 条 SQL + show stats 自己）
        long after = ServerMetrics.get().totalQueries.get();
        assertTrue("queries.total should grow, before=" + before + " after=" + after,
            after >= before + 5);
    }

    @Test
    public void showStatsAfterError() {
        Executor exe = open();
        long beforeErrors = ServerMetrics.get().totalErrors.get();
        // 故意打错
        Package p = exe.execute("select * from no_such_table");
        assertTrue(p.isError());
        long afterErrors = ServerMetrics.get().totalErrors.get();
        assertTrue(afterErrors >= beforeErrors + 1);
    }
}
