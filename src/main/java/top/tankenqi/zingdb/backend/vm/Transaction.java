package top.tankenqi.zingdb.backend.vm;

import java.util.HashMap;
import java.util.Map;

import top.tankenqi.zingdb.backend.tm.TransactionManagerImpl;

// vm 对一个事务的抽象
public class Transaction {
    public long xid;
    public int level; // 事务隔离级别
    public Map<Long, Boolean> snapshot; // （当前快照中的活动事务）
    public Exception err;
    public boolean autoAborted;

    public static Transaction newTransaction(long xid, int level, Map<Long, Transaction> active) {
        Transaction t = new Transaction();
        t.xid = xid;
        t.level = level;
        if(level != 0) {
            t.snapshot = new HashMap<>();
            for(Long x : active.keySet()) {
                t.snapshot.put(x, true);
            }
        }
        return t;
    }

    public boolean isInSnapshot(long xid) {
        if(xid == TransactionManagerImpl.SUPER_XID) {
            return false;
        }
        return snapshot.containsKey(xid);
    }
}
