package top.tankenqi.zingdb.backend.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

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
 * 端到端 SQL 测试（阶段 2 关键能力）：
 *   1. 非索引字段查询走表扫描，而非抛 "Field not indexed"
 *   2. AND/OR 不再误丢/误增结果（不同字段 AND、相同字段 OR）
 *   3. UPDATE 后旧索引项失效、新索引项命中
 *   4. DROP TABLE 后该表不可见，重启后仍不可见（持久化）
 *   5. 新类型 float64 / bool / datetime 可写可读
 *   6. ORDER BY / LIMIT / OFFSET / COUNT(*)
 *
 * 通过 Executor.execute 直接驱动，不走网络，方便断言。
 */
public class EndToEndSqlTest {

    private String dir;
    private String path;

    @Before
    public void setUp() throws Exception {
        File tmp = Files.createTempDirectory("zingdb-e2e-").toFile();
        dir = tmp.getAbsolutePath();
        path = dir + "/db";
        // 初始化空库
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

    /** 重新打开数据库返回一个 fresh Executor。 */
    private Executor reopen() {
        TransactionManager tm = TransactionManager.open(path);
        DataManager dm = DataManager.open(path, 1L << 20, tm);
        VersionManager vm = VersionManager.newVersionManager(tm, dm);
        TableManager tbm = TableManager.open(path, vm, dm);
        return new Executor(tbm);
    }

    // ---------- helpers ----------

    private static ResultSet rs(Executor exe, String sql) {
        Package p = exe.execute(sql);
        assertTrue("expected RESULT_SET, got " + p.getType() + " err=" + p.getMessage(), p.isResultSet());
        return p.getResultSet();
    }

    private static long ok(Executor exe, String sql) {
        Package p = exe.execute(sql);
        assertTrue("expected OK, got err " + p.getMessage(), p.isOk());
        return p.getRowsAffected();
    }

    private static void err(Executor exe, String sql) {
        Package p = exe.execute(sql);
        assertTrue("expected ERROR, got " + p.getType(), p.isError());
    }

    // ---------- tests ----------

    @Test
    public void nonIndexedFieldUsesTableScan() {
        Executor exe = reopen();
        ok(exe, "create table users id int32, name string, age int32, (index id)");
        ok(exe, "insert into users values (1, 'alice', 23)");
        ok(exe, "insert into users values (2, 'bob',   30)");
        ok(exe, "insert into users values (3, 'carol', 17)");

        // 老实现这条会抛 "Field not indexed"；新实现走表扫描
        ResultSet r = rs(exe, "select name, age from users where age > 18");
        assertEquals(2, r.rowCount());
    }

    @Test
    public void andOnDifferentFields() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, age int32, (index id age)");
        ok(exe, "insert into u values (1, 10)");
        ok(exe, "insert into u values (2, 20)");
        ok(exe, "insert into u values (3, 30)");
        // id > 1 AND age < 30 → 应只返回 id=2
        ResultSet r = rs(exe, "select id from u where id > 1 and age < 30");
        assertEquals(1, r.rowCount());
        assertEquals(2, ((Integer) r.getRows().get(0)[0]).intValue());
    }

    @Test
    public void orDeduplicates() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, (index id)");
        ok(exe, "insert into u values (1)");
        ok(exe, "insert into u values (2)");
        ok(exe, "insert into u values (3)");
        // id = 1 OR id < 5 → 应该是 {1,2,3} 而不是 {1,1,2,3}
        ResultSet r = rs(exe, "select id from u where id = 1 or id < 5");
        assertEquals(3, r.rowCount());
    }

    @Test
    public void inAndBetweenAndLike() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, name string, (index id)");
        ok(exe, "insert into u values (1, 'alice')");
        ok(exe, "insert into u values (2, 'alex')");
        ok(exe, "insert into u values (3, 'bob')");
        ok(exe, "insert into u values (4, 'carol')");

        assertEquals(2, rs(exe, "select id from u where id in (1, 3)").rowCount());
        assertEquals(3, rs(exe, "select id from u where id between 1 and 3").rowCount());
        // LIKE 走 evaluator（fullScan + filter）
        assertEquals(2, rs(exe, "select id from u where name like 'al%'").rowCount());
        assertEquals(1, rs(exe, "select id from u where name not like 'a%' and id < 4").rowCount());
    }

    @Test
    public void updateMaintainsIndexConsistency() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, (index id)");
        ok(exe, "insert into u values (10)");
        ok(exe, "insert into u values (20)");

        // 把 id=10 改成 30
        long n = ok(exe, "update u set id = 30 where id = 10");
        assertEquals(1, n);

        // 老值 10 应该查不到了（老实现下索引脏数据会让这条返回 1 行）
        assertEquals(0, rs(exe, "select id from u where id = 10").rowCount());
        assertEquals(1, rs(exe, "select id from u where id = 20").rowCount());
        assertEquals(1, rs(exe, "select id from u where id = 30").rowCount());
        assertEquals(2, rs(exe, "select id from u where id > 0").rowCount());
    }

    @Test
    public void dropTableAndRecreate() {
        Executor exe = reopen();
        ok(exe, "create table t a int32, (index a)");
        ok(exe, "insert into t values (1)");
        assertEquals(1, rs(exe, "select a from t where a > 0").rowCount());

        long n = ok(exe, "drop table t");
        assertEquals(1, n);
        err(exe, "select a from t where a > 0");

        // 重启后仍然不可见
        Executor exe2 = reopen();
        err(exe2, "select a from t where a > 0");

        // 重新 create 同名表应该可以
        ok(exe2, "create table t a int32, (index a)");
        ok(exe2, "insert into t values (42)");
        ResultSet r = rs(exe2, "select a from t where a > 0");
        assertEquals(1, r.rowCount());
        assertEquals(42, ((Integer) r.getRows().get(0)[0]).intValue());
    }

    @Test
    public void newTypesFloatBoolDatetime() {
        Executor exe = reopen();
        ok(exe, "create table m id int32, score float64, ok bool, born datetime, (index id)");
        ok(exe, "insert into m values (1, 3.14,   true,  1700000000000)");
        ok(exe, "insert into m values (2, -1.5,   false, '2024-01-15 10:30:00')");

        ResultSet r = rs(exe, "select id, score, ok, born from m where id > 0 order by id");
        assertEquals(2, r.rowCount());
        Object[] row0 = r.getRows().get(0);
        assertEquals(3.14, (Double) row0[1], 1e-9);
        assertEquals(Boolean.TRUE, row0[2]);
        assertEquals(1700000000000L, ((Long) row0[3]).longValue());

        Object[] row1 = r.getRows().get(1);
        assertEquals(-1.5, (Double) row1[1], 1e-9);
        assertEquals(Boolean.FALSE, row1[2]);
        assertNotNull(row1[3]);
    }

    @Test
    public void orderByLimitOffsetCount() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, age int32, (index id)");
        for (int i = 1; i <= 5; i++) {
            ok(exe, "insert into u values (" + i + ", " + (100 - i * 10) + ")");
        }

        // ORDER BY age ASC（默认升序）
        ResultSet r1 = rs(exe, "select id, age from u where id > 0 order by age");
        List<Object[]> rows = r1.getRows();
        assertEquals(5, rows.size());
        assertEquals(50, ((Integer) rows.get(0)[1]).intValue()); // 最小 age
        assertEquals(90, ((Integer) rows.get(4)[1]).intValue());

        // LIMIT 2 OFFSET 1
        ResultSet r2 = rs(exe, "select id from u where id > 0 order by id limit 2 offset 1");
        assertEquals(2, r2.rowCount());
        assertEquals(2, ((Integer) r2.getRows().get(0)[0]).intValue());
        assertEquals(3, ((Integer) r2.getRows().get(1)[0]).intValue());

        // COUNT(*)
        ResultSet r3 = rs(exe, "select count(*) from u where age >= 70");
        assertEquals(1, r3.rowCount());
        assertEquals(3L, ((Long) r3.getRows().get(0)[0]).longValue());
    }

    @Test
    public void parensAndNotPredicates() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, age int32, (index id)");
        ok(exe, "insert into u values (1, 10)");
        ok(exe, "insert into u values (2, 20)");
        ok(exe, "insert into u values (3, 30)");

        // (id = 1 or id = 3) and age > 5
        assertEquals(2, rs(exe, "select id from u where (id = 1 or id = 3) and age > 5").rowCount());
        // not age > 15
        assertEquals(1, rs(exe, "select id from u where not age > 15 and id > 0").rowCount());
    }

    @Test
    public void deleteReleasesIndex() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, (index id)");
        ok(exe, "insert into u values (1)");
        ok(exe, "insert into u values (2)");
        assertEquals(2, rs(exe, "select id from u where id > 0").rowCount());

        long n = ok(exe, "delete from u where id = 1");
        assertEquals(1, n);
        ResultSet r = rs(exe, "select id from u where id > 0");
        assertEquals(1, r.rowCount());
        assertEquals(2, ((Integer) r.getRows().get(0)[0]).intValue());
    }

    @Test
    public void descShowTables() {
        Executor exe = reopen();
        ok(exe, "create table u id int32, name string, (index id)");
        ResultSet d = rs(exe, "desc u");
        assertEquals(2, d.rowCount());
        assertEquals("id", d.getRows().get(0)[0]);
        assertEquals("int32", d.getRows().get(0)[1]);
        assertEquals(Boolean.TRUE, d.getRows().get(0)[2]);
        assertEquals("name", d.getRows().get(1)[0]);
        assertEquals(Boolean.FALSE, d.getRows().get(1)[2]);

        ResultSet sh = rs(exe, "show");
        assertEquals(1, sh.rowCount());
        // 默认情况下不在事务里 → showRS 看到 tableCache 中那一张
        Object[] row = sh.getRows().get(0);
        assertTrue(((String) row[0]).contains("u"));
    }

    @Test
    public void isNullExprOnNonNullData() {
        // ZingDB 当前 entry 编码不支持持久化 SQL NULL（无 null bitmap），所有列值都是非空的。
        // 因此 IS NULL 应当返回 0 行，IS NOT NULL 返回全部行。这里只验证表达式被正确解析与求值。
        Executor exe = reopen();
        ok(exe, "create table u id int32, name string, (index id)");
        ok(exe, "insert into u values (1, 'alice')");
        ok(exe, "insert into u values (2, 'bob')");

        assertEquals(0, rs(exe, "select id from u where name is null").rowCount());
        assertEquals(2, rs(exe, "select id from u where name is not null").rowCount());
        assertEquals(1, rs(exe, "select id from u where name = 'alice'").rowCount());
    }
}
