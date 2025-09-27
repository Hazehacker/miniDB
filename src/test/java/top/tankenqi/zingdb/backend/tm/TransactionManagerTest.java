package top.tankenqi.zingdb.backend.tm;

import java.io.File;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

public class TransactionManagerTest {

    static Random random = new SecureRandom();

    private int transCnt = 0; // 当前活跃事务的数量
    private int noWorkers = 50; // 并发线程
    private int noWorks = 3000; // 每个线程的操作次数

    // 锁，防止多个线程同时访问 transCnt
    private final Lock transCntLock = new ReentrantLock();

    private TransactionManager tmger;
    // transMap 用的 ConcurrentHashMap，本身就是线程安全的
    private Map<Long, Byte> transMap;
    private CountDownLatch cdl; // 用于等待所有线程完成

    @Test
    public void testMultiThread() {
        tmger = TransactionManager.create("/tmp/tranmger_test");
        transMap = new ConcurrentHashMap<>();
        cdl = new CountDownLatch(noWorkers);
        for (int i = 0; i < noWorkers; i++) {
            Runnable r = () -> worker();
            new Thread(r).run();
        }
        try {
            cdl.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assert new File("/tmp/tranmger_test.xid").delete();
    }

    private void worker() {
        boolean inTrans = false; // 是否在事务中
        long transXID = 0; // 当前事务的XID
        for (int i = 0; i < noWorks; i++) {
            int op = Math.abs(random.nextInt(6));
            if (op == 0) { // 1/6的概率：事务控制操作
                if (inTrans == false) { // 如果不在事务中，则开始一个新事务
                    long xid = tmger.begin();
                    transMap.put(xid, (byte) 0);

                    transCntLock.lock();
                    transCnt++;
                    transCntLock.unlock();

                    transXID = xid;
                    inTrans = true;
                } else { // 如果已经在事务中，则提交或回滚当前事务
                    int status = (random.nextInt(Integer.MAX_VALUE) % 2) + 1;
                    switch (status) {
                        case 1:
                            tmger.commit(transXID);
                            break;
                        case 2:
                            tmger.abort(transXID);
                            break;
                    }
                    transMap.put(transXID, (byte) status);
                    inTrans = false;
                }
            } else { // 5/6的概率：检查事务状态
                if (transCnt > 0) {
                    long xid = (long) ((random.nextInt(Integer.MAX_VALUE) % transCnt) + 1);
                    byte status = transMap.get(xid);

                    boolean ok = false;
                    switch (status) {
                        case 0:
                            ok = tmger.isActive(xid);
                            break;
                        case 1:
                            ok = tmger.isCommitted(xid);
                            break;
                        case 2:
                            ok = tmger.isAborted(xid);
                            break;
                    }
                    assert ok;
                }
            }
        }
        cdl.countDown();
    }
}
