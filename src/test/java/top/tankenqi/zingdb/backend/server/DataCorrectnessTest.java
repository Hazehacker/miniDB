package top.tankenqi.zingdb.backend.server;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

/** 使用真实文件、SQL 和 MVCC 复现数据损坏、漏行及回滚错误。 */
public class DataCorrectnessTest {
    private Path directory;
    private TransactionManager tm;
    private DataManager dm;
    private TableManager tables;
    private Executor executor;
    private final List<Executor> sessions = new ArrayList<>();

    @Before public void create() throws Exception {
        directory = Files.createTempDirectory("minidb-correctness-");
        open(true);
    }
    private void open(boolean create) {
        String path = directory.resolve("db").toString();
        tm = create ? TransactionManager.create(path) : TransactionManager.open(path);
        dm = create ? DataManager.create(path, 1 << 20, tm) : DataManager.open(path, 1 << 20, tm);
        VersionManager vm = VersionManager.newVersionManager(tm, dm);
        tables = create ? TableManager.create(path, vm, dm) : TableManager.open(path, vm, dm);
        executor = session();
    }
    private Executor session() {
        Executor result = new Executor(tables);
        sessions.add(result);
        return result;
    }
    private void reopen() {
        for (Executor session : sessions) session.close();
        sessions.clear();
        dm.close(); tm.close();
        open(false);
    }
    @After public void cleanup() throws Exception {
        for (Executor session : sessions) session.close();
        // 关闭也属于验证范围，不吞掉缓存刷写或释放错误。
        dm.close();
        tm.close();
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(directory);
    }
    private static void ok(Executor e, String sql) {
        Package p = e.execute(sql);
        assertTrue(sql + " -> " + p.getMessage(), p.isOk());
    }
    private void ok(String sql) { ok(executor, sql); }
    private static ResultSet rows(Executor e, String sql) {
        Package p = e.execute(sql);
        assertTrue(sql + " -> " + p.getMessage(), p.isResultSet());
        return p.getResultSet();
    }
    private ResultSet rows(String sql) { return rows(executor, sql); }
    private void error(String sql) {
        Package p = executor.execute(sql);
        assertTrue("must reject: " + sql, p.isError());
    }
    private static void ids(ResultSet result, Integer... expected) {
        List<Object> actual = new ArrayList<>();
        for (Object[] row : result.getRows()) actual.add(row[0]);
        assertEquals(Arrays.asList(expected), actual);
    }
    @Test public void unicodeDoesNotCorruptFollowingColumns() throws Exception {
        ok("create table t id int32, name string, age int32, (index id)");
        ok("insert into t values (1, '张三🙂', 17)");
        assertArrayEquals(new Object[]{1, "张三🙂", 17}, rows("select * from t").getRows().get(0));
        reopen();
        assertArrayEquals(new Object[]{1, "张三🙂", 17}, rows("select * from t").getRows().get(0));
    }
    @Test public void doubledQuoteAndEmptyStringRoundTrip() {
        ok("create table t id int32, name string, (index id)");
        ok("insert into t values (1, 'Tom''s book')");
        ok("insert into t values (2, '')");
        assertArrayEquals(new Object[]{1, "Tom's book"}, rows("select * from t where name = 'Tom''s book'").getRows().get(0));
        assertArrayEquals(new Object[]{2, ""}, rows("select * from t where name = ''").getRows().get(0));
    }
    @Test public void quotedNullRemainsAString() {
        ok("create table t id int32, name string, (index id)");
        ok("insert into t values (1, 'null')");
        ids(rows("select id from t where name = 'null'"), 1);
    }
    @Test public void noIndexSupportsReadUpdateDeleteAndRestart() {
        ok("create table t id int32, name string, (index)");
        ok("insert into t values (1, 'first')");
        ok("insert into t values (2, 'second')");
        ids(rows("select id from t order by id"), 1, 2);
        ok("update t set name = 'changed' where id = 1");
        ok("delete from t where id = 2");
        reopen();
        assertArrayEquals(new Object[]{1, "changed"}, rows("select * from t").getRows().get(0));
    }
    @Test public void tableScansKeepRowsInTheirOwnTable() {
        ok("create table a id int32, (index)"); ok("create table b id int32, (index)");
        ok("insert into a values (1)"); ok("insert into b values (2)");
        ids(rows("select id from a"), 1); ids(rows("select id from b"), 2);
    }
    @Test public void negativeKeysAreIncludedInScansAndRanges() {
        ok("create table t id int32, (index id)");
        for (int id : new int[]{-2, -1, 0, 1}) ok("insert into t values (" + id + ")");
        ids(rows("select id from t order by id"), -2, -1, 0, 1);
        ids(rows("select id from t where id < 0 order by id"), -2, -1);
        ids(rows("select id from t where id <= -1 order by id"), -2, -1);
        ids(rows("select id from t where id != 0 order by id"), -2, -1, 1);
    }
    @Test public void stringIndexDoesNotDetermineLexicalRange() {
        ok("create table t id int32, name string, (index name)");
        ok("insert into t values (1, 'z')"); ok("insert into t values (2, 'aa')");
        ids(rows("select id from t where name < 'b'"), 2);
    }
    @Test public void floatIndexDoesNotLoseNegativeRange() {
        ok("create table t id int32, value float64, (index value)");
        ok("insert into t values (1, -2.5)"); ok("insert into t values (2, 1.0)");
        ids(rows("select id from t where value < 0"), 1);
    }
    private void baseline() {
        ok("create table t id int32, value string, (index id)");
        ok("insert into t values (1, 'original')");
    }
    @Test public void deleteAbortRestoresIndexedAndUnfilteredReads() {
        baseline(); ok("begin"); ok("delete from t where id = 1"); ok("abort");
        ids(rows("select id from t where id = 1"), 1);
        reopen(); ids(rows("select id from t"), 1);
    }
    @Test public void updateAbortRestoresBothIndexKeys() {
        baseline(); ok("begin"); ok("update t set id = 2 where id = 1"); ok("abort");
        ids(rows("select id from t where id = 1"), 1);
        ids(rows("select id from t where id = 2"));
        reopen(); ids(rows("select id from t"), 1);
    }
    @Test public void repeatableReadKeepsOldIndexAfterOtherCommit() {
        baseline(); Executor reader = session();
        ok(reader, "begin isolation level repeatable read");
        ids(rows(reader, "select id from t where id = 1"), 1);
        ok("update t set id = 2 where id = 1");
        ids(rows(reader, "select id from t where id = 1"), 1);
        ids(rows(reader, "select id from t where id = 2"));
        ok(reader, "commit"); ids(rows("select id from t where id = 2"), 2);
    }
    @Test public void abortedInsertRemainsInvisibleAfterRestart() {
        baseline(); ok("begin"); ok("insert into t values (2, 'aborted')"); ok("abort");
        reopen(); ids(rows("select id from t order by id"), 1);
    }
    @Test public void emptyTablesStillValidatePredicatesAndSortColumns() {
        ok("create table t id int32, age int32, (index id)");
        error("select * from t where missing = 1");
        error("select * from t where age > 'abc'");
        error("select * from t where id = 1 or missing = 2");
        error("select * from t order by missing");
        error("update t set age = 3 where missing = 1");
        error("delete from t where missing = 1");
    }
    @Test public void integersAboveDoublePrecisionStayDistinct() {
        ok("create table t id int32, value int64, (index id)");
        ok("insert into t values (1, 9007199254740992)");
        ok("insert into t values (2, 9007199254740993)");
        ids(rows("select id from t where value = 9007199254740993"), 2);
    }

    @Test public void rowDirectorySpansPagesAndSurvivesRestart() {
        ok("create table t id int32, value string, (index)");
        ok("begin");
        String padding = new String(new char[180]).replace('\0', 'x');
        for (int id = 0; id < 130; id++) ok("insert into t values (" + id + ", '" + padding + "')");
        ok("commit");
        reopen();
        assertEquals(130L, rows("select count(*) from t").getRows().get(0)[0]);
        ids(rows("select id from t where id >= 128 order by id"), 128, 129);
    }
    @Test public void duplicateIndexKeysRemainCompleteAfterSplit() {
        ok("create table t id int32, value int32, (index value)");
        ok("begin");
        for (int id = 0; id < 80; id++) ok("insert into t values (" + id + ", -1)");
        ok("commit");
        assertEquals(80L, rows("select count(*) from t where value = -1").getRows().get(0)[0]);
    }

    @Test public void maximumLongIndexKeyWorksAfterSplit() {
        ok("create table t id int32, value int64, (index value)");
        ok("begin");
        for (int id = 0; id < 70; id++) ok("insert into t values (" + id + ", " + id + ")");
        ok("insert into t values (100, 9223372036854775807)");
        ok("insert into t values (101, -9223372036854775808)");
        ok("commit");
        ids(rows("select id from t where value = 9223372036854775807"), 100);
        ids(rows("select id from t where value < -9223372036854775808"));
        ids(rows("select id from t where value >= 9223372036854775807"), 100);
    }
}
