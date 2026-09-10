package top.zhongnan.minidb.engine;

import java.io.File;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import top.zhongnan.minidb.storage.DataManager;
import top.zhongnan.minidb.engine.table.TableManager;
import top.zhongnan.minidb.engine.tx.TransactionManager;
import top.zhongnan.minidb.engine.tx.VersionManager;

public class ExecutorTest {
    String path = "/tmp/minidb";
    long mem = (1 << 20) * 64;

    String CREATE_TABLE = "create table test_table id int32 (index id)";
    String INSERT = "insert into test_table values 2333";

    private Executor testCreate() throws Exception {
        TransactionManager tm = TransactionManager.create(path);
        DataManager dm = DataManager.create(path, mem, tm);
        VersionManager vm = VersionManager.newVersionManager(tm, dm);
        TableManager tbm = TableManager.create(path, vm, dm);
        Executor exe = new Executor(tbm);
        exe.execute(CREATE_TABLE);
        return exe;
    }

    private void testInsert(Executor exe, int times, int no) {
        for (int i = 0; i < times; i++) {
            exe.execute(INSERT);
        }
    }

    @Test
    public void testInsert10000() throws Exception {
        Executor exe = testCreate();
        testInsert(exe, 10000, 1);
        cleanup();
    }

    private void testMultiInsert(int total, int noWorkers) throws Exception {
        Executor exe = testCreate();
        TableManager tbm = exe.tbm;
        int w = total / noWorkers;
        CountDownLatch cdl = new CountDownLatch(noWorkers);
        for (int i = 0; i < noWorkers; i++) {
            final int no = i;
            new Thread(() -> {
                try {
                    testInsert(new Executor(tbm), w, no);
                } finally {
                    cdl.countDown();
                }
            }).start();
        }
        cdl.await();
    }

    @Test
    public void test100000With4() throws Exception {
        testMultiInsert(10000, 4);
        cleanup();
    }

    private void cleanup() {
        new File(path + ".db").delete();
        new File(path + ".bt").delete();
        new File(path + ".log").delete();
        new File(path + ".xid").delete();
    }
}
