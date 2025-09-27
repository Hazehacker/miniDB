package top.tankenqi.zingdb.backend.vm;

import top.tankenqi.zingdb.backend.tm.TransactionManager;

public class Visibility {

    public static boolean isVersionSkip(TransactionManager tm, Transaction t, Entry e) {
        long xmax = e.getXmax();
        if (t.level == 0) {
            return false; // 读已提交隔离级别下，允许版本跳跃
        } else {
            return tm.isCommitted(xmax) && (xmax > t.xid || t.isInSnapshot(xmax));
        }
    }

    public static boolean isVisible(TransactionManager tm, Transaction t, Entry e) {
        if (t.level == 0) {
            return readCommitted(tm, t, e);
        } else {
            return repeatableRead(tm, t, e);
        }
    }

    /**
     * 读已提交隔离级别下的可见性判断
     * (XMIN == Ti and XMAX == NULL） // 由Ti创建且还未被删除
     * or
     * 由一个已提交的事务创建且尚未删除“或者”由一个未提交的事务删除
     * (XMIN is commited and (XMAX == NULL or (XMAX != Ti and XMAX is not
     * commited)))
     */
    private static boolean readCommitted(TransactionManager tm, Transaction t, Entry e) {
        long xid = t.xid;
        long xmin = e.getXmin();
        long xmax = e.getXmax();
        if (xmin == xid && xmax == 0)
            return true;

        if (tm.isCommitted(xmin)) {
            if (xmax == 0)
                return true;
            if (xmax != xid) {
                if (!tm.isCommitted(xmax)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 可重复读隔离级别下的可见性判断
     */
    private static boolean repeatableRead(TransactionManager tm, Transaction t, Entry e) {
        long xid = t.xid;
        long xmin = e.getXmin();
        long xmax = e.getXmax();
        if (xmin == xid && xmax == 0)
            return true;

        if (tm.isCommitted(xmin) && xmin < xid && !t.isInSnapshot(xmin)) {
            if (xmax == 0)
                return true;
            if (xmax != xid) {
                if (!tm.isCommitted(xmax) || xmax > xid || t.isInSnapshot(xmax)) {
                    return true;
                }
            }
        }
        return false;
    }

}
