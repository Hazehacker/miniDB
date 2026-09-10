package top.zhongnan.minidb.backend.tbm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import top.zhongnan.minidb.backend.common.SubArray;
import top.zhongnan.minidb.backend.dm.DataManager;
import top.zhongnan.minidb.backend.dm.dataItem.DataItem;
import top.zhongnan.minidb.backend.tm.TransactionManagerImpl;
import top.zhongnan.minidb.backend.utils.Parser;

/**
 * 表的持久化行目录，不依赖用户字段或业务索引。
 * 根记录保存链表头；每个节点保存 [rowUid, nextUid]。节点只追加，
 * MVCC 负责过滤未提交、已回滚和已删除版本，后续 VACUUM 才能回收历史项。
 * 根和节点沿用 DM 的 WAL，以 SUPER_XID 记录结构变化，避免业务回滚断开链表。
 */
final class RowDirectory {
    private final DataManager dm;
    final long rootUid;

    RowDirectory(DataManager dm, long rootUid) {
        this.dm = dm;
        this.rootUid = rootUid;
    }

    static RowDirectory create(DataManager dm) throws Exception {
        return new RowDirectory(dm, dm.insert(TransactionManagerImpl.SUPER_XID, new byte[8]));
    }

    synchronized void append(long rowUid) throws Exception {
        DataItem root = dm.read(rootUid);
        if (root == null) throw new IllegalStateException("Missing row directory root");
        try {
            SubArray data = root.data();
            byte[] node = new byte[16];
            System.arraycopy(Parser.long2Byte(rowUid), 0, node, 0, 8);
            System.arraycopy(data.raw, data.start, node, 8, 8);
            long nodeUid = dm.insert(TransactionManagerImpl.SUPER_XID, node);
            root.before();
            System.arraycopy(Parser.long2Byte(nodeUid), 0, data.raw, data.start, 8);
            root.after(TransactionManagerImpl.SUPER_XID);
        } finally {
            root.release();
        }
    }

    synchronized List<Long> scan() throws Exception {
        DataItem root = dm.read(rootUid);
        if (root == null) throw new IllegalStateException("Missing row directory root");
        long next;
        try { next = readLong(root.data(), 0); }
        finally { root.release(); }
        List<Long> rows = new ArrayList<>();
        while (next != 0) {
            DataItem node = dm.read(next);
            if (node == null) throw new IllegalStateException("Missing row directory node: " + next);
            try {
                rows.add(readLong(node.data(), 0));
                next = readLong(node.data(), 8);
            } finally { node.release(); }
        }
        // 链表头插，恢复插入顺序，避免无 ORDER BY 的旧演示反向排列。
        Collections.reverse(rows);
        return rows;
    }

    private static long readLong(SubArray data, int offset) {
        return Parser.parseLong(Arrays.copyOfRange(data.raw, data.start + offset, data.start + offset + 8));
    }
}
