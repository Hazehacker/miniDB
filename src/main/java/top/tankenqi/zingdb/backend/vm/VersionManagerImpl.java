package top.tankenqi.zingdb.backend.vm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.tankenqi.zingdb.backend.common.AbstractCache;
import top.tankenqi.zingdb.backend.dm.DataManager;
import top.tankenqi.zingdb.backend.tm.TransactionManager;
import top.tankenqi.zingdb.backend.tm.TransactionManagerImpl;
import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.common.Error;

public class VersionManagerImpl extends AbstractCache<Entry> implements VersionManager {

    TransactionManager tm;
    DataManager dm;
    Map<Long, Transaction> activeTransaction;
    Lock lock;
    LockTable lt;

    public VersionManagerImpl(TransactionManager tm, DataManager dm) {
        super(0);
        this.tm = tm;
        this.dm = dm;
        this.activeTransaction = new HashMap<>();
        activeTransaction.put(TransactionManagerImpl.SUPER_XID,
                Transaction.newTransaction(TransactionManagerImpl.SUPER_XID, 0, null));
        this.lock = new ReentrantLock();
        this.lt = new LockTable();
    }

    /**
     * 读取一个版本，需要先检查事务的状态，
     * 然后从缓存中获取到 Entry 对象，
     * 最后检查可见性，如果可见，则返回数据
     */
    @Override
    public byte[] read(long xid, long uid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        // 检查事务的状态是否异常
        if (t.err != null) {
            throw t.err;
        }

        Entry entry = null;
        try {
            entry = super.get(uid);
        } catch (Exception e) {
            if (e == Error.NullEntryException) {
                return null;
            } else {
                throw e;
            }
        }
        try {
            if (Visibility.isVisible(tm, t, entry)) {
                return entry.data();
            } else {
                return null;
            }
        } finally {
            entry.release();
        }
    }

    /**
     * 将数据包裹成 Entry，无脑交给 DM 插入即可
     */
    @Override
    public long insert(long xid, byte[] data) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        if (t.err != null) {
            throw t.err;
        }

        byte[] raw = Entry.wrapEntryRaw(xid, data);
        return dm.insert(xid, raw);
    }

    /**
     * 一是可见性判断，二是获取资源的锁，
     * 三是版本跳跃判断，四是设置 XMAX
     */
    @Override
    public boolean delete(long xid, long uid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        if (t.err != null) {
            throw t.err;
        }
        Entry entry = null;
        try {
            entry = super.get(uid);
        } catch (Exception e) {
            if (e == Error.NullEntryException) {
                return false;
            } else {
                throw e;
            }
        }
        try {
            if (!Visibility.isVisible(tm, t, entry)) {
                return false;
            }
            Lock l = null;
            try {
                l = lt.add(xid, uid);
            } catch (Exception e) {
                // 设置事务的状态为异常状态
                t.err = Error.ConcurrentUpdateException;
                internAbort(xid, true);
                t.autoAborted = true;
                throw t.err;
            }
            if (l != null) {
                l.lock();
                l.unlock();
            }

            if (entry.getXmax() == xid) {
                return false;
            }

            if (Visibility.isVersionSkip(tm, t, entry)) {
                // 设置事务的状态为异常状态
                t.err = Error.ConcurrentUpdateException;
                internAbort(xid, true);
                t.autoAborted = true;
                throw t.err;
            }

            entry.setXmax(xid);
            return true;

        } finally {
            entry.release();
        }
    }

    /**
     * 开启一个事务，并初始化事务的结构，
     * 将其存放在 activeTransaction 中，用于检查和快照使用
     */
    @Override
    public long begin(int level) {
        lock.lock();
        try {
            long xid = tm.begin();
            Transaction t = Transaction.newTransaction(xid, level, activeTransaction);
            activeTransaction.put(xid, t);
            return xid;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 提交一个事务，主要就是 free 掉相关的结构，
     * 并且释放持有的锁，并修改 TM 状态
     */
    @Override
    public void commit(long xid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        try {
            if (t.err != null) {
                throw t.err;
            }
        } catch (NullPointerException n) {
            System.out.println(xid);
            System.out.println(activeTransaction.keySet());
            Panic.panic(n);
        }

        lock.lock();
        activeTransaction.remove(xid);
        lock.unlock();

        lt.remove(xid);
        tm.commit(xid);
    }

    /**
     * abort 事务的方法则有两种，手动和自动：
     * 
     * 手动指的是调用 abort() 方法；
     * 
     * 而自动，则是在事务被检测出出现死锁时，会自动撤销回滚事务，
     * 或者出现版本跳跃时，也会自动回滚；
     */
    @Override
    public void abort(long xid) {
        internAbort(xid, false);
    }

    private void internAbort(long xid, boolean autoAborted) {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        if (!autoAborted) {
            activeTransaction.remove(xid);
        }
        lock.unlock();

        if (t.autoAborted)
            return;
        lt.remove(xid);
        tm.abort(xid);
    }

    public void releaseEntry(Entry entry) {
        super.release(entry.getUid());
    }

    @Override
    protected Entry getForCache(long uid) throws Exception {
        Entry entry = Entry.loadEntry(this, uid);
        if (entry == null) {
            throw Error.NullEntryException;
        }
        return entry;
    }

    @Override
    protected void releaseForCache(Entry entry) {
        entry.remove();
    }

}
