package top.tankenqi.zingdb.backend.dm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.primitives.Bytes;

import top.tankenqi.zingdb.backend.common.SubArray;
import top.tankenqi.zingdb.backend.dm.dataItem.DataItem;
import top.tankenqi.zingdb.backend.dm.logger.Logger;
import top.tankenqi.zingdb.backend.dm.page.Page;
import top.tankenqi.zingdb.backend.dm.page.PageX;
import top.tankenqi.zingdb.backend.dm.pageCache.PageCache;
import top.tankenqi.zingdb.backend.tm.TransactionManager;
import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.backend.utils.Parser;

public class Recover {

    private static final byte LOG_TYPE_INSERT = 0;
    private static final byte LOG_TYPE_UPDATE = 1;

    private static final int REDO = 0;
    private static final int UNDO = 1;

    /**
     * REDO 用 raw 进行重放
     * UNDO 则是将 raw 标记为无效，然后进行重放
     */
    static class InsertLogInfo {
        long xid;
        int pgno;
        short offset;
        byte[] raw;
    }

    /**
     * REDO 用 newRaw，UNDO 用 oldRaw
     */
    static class UpdateLogInfo {
        long xid;
        int pgno;
        short offset;
        byte[] oldRaw;
        byte[] newRaw;
    }

    public static void recover(TransactionManager tm, Logger lg, PageCache pc) {
        System.out.println("Recovering...");

        /**
         * 重置日志读取位置，从4字节位置开始读取
         * 日志格式文件存储格式：
         * [XChecksum] [Log1] [Log2] ... [LogN] [BadTail]，XChecksum是4字节
         */
        lg.rewind();
        int maxPgno = 0;
        while (true) {
            /**
             * 读取下一个日志，log是每条日志的[Data]部分,
             * 每条日志的格式：[Size] [Checksum] [Data]
             */
            byte[] log = lg.next();
            if (log == null)
                break;
            int pgno;
            if (isInsertLog(log)) {
                InsertLogInfo li = parseInsertLog(log);
                pgno = li.pgno;
            } else {
                UpdateLogInfo li = parseUpdateLog(log);
                pgno = li.pgno;
            }
            if (pgno > maxPgno) {
                maxPgno = pgno;
            }
        }
        if (maxPgno == 0) {
            maxPgno = 1;
        }

        pc.truncateByBgno(maxPgno);
        System.out.println("Truncate to " + maxPgno + " pages.");

        // 重做已完成（非活跃）事务
        redoTranscations(tm, lg, pc);
        System.out.println("Redo Transactions Over.");

        // 回滚未完成（活跃）事务
        undoTranscations(tm, lg, pc);
        System.out.println("Undo Transactions Over.");

        System.out.println("Recovery Over.");
    }

    /**
     * 对非活跃事务（即已提交或已回滚完成）的日志重做。
     * 
     * @param tm
     * @param lg
     * @param pc
     */
    private static void redoTranscations(TransactionManager tm, Logger lg, PageCache pc) {
        lg.rewind();
        while (true) {
            byte[] log = lg.next();
            if (log == null)
                break;
            if (isInsertLog(log)) { // 如果是插入操作，按新数据重放
                InsertLogInfo li = parseInsertLog(log);
                long xid = li.xid;
                if (!tm.isActive(xid)) {
                    doInsertLog(pc, log, REDO);
                }
            } else { // 如果是更新操作，按新值重放
                UpdateLogInfo xi = parseUpdateLog(log);
                long xid = xi.xid;
                if (!tm.isActive(xid)) {
                    doUpdateLog(pc, log, REDO);
                }
            }
        }
    }

    /**
     * 筛出活跃事务的日志，并按事务聚合，
     * 随后对每个活跃事务“逆序回放”以撤销其影响
     * 
     * @param tm
     * @param lg
     * @param pc
     */
    private static void undoTranscations(TransactionManager tm, Logger lg, PageCache pc) {
        Map<Long, List<byte[]>> logCache = new HashMap<>();
        lg.rewind();
        while (true) {
            byte[] log = lg.next();
            if (log == null)
                break;
            if (isInsertLog(log)) { // 如果是插入操作，撤销时把插入的数据标记为无效
                InsertLogInfo li = parseInsertLog(log);
                long xid = li.xid;
                if (tm.isActive(xid)) {
                    if (!logCache.containsKey(xid)) {
                        logCache.put(xid, new ArrayList<>());
                    }
                    logCache.get(xid).add(log);
                }
            } else { // 如果是更新操作，撤销时把新值恢复成旧值
                UpdateLogInfo xi = parseUpdateLog(log);
                long xid = xi.xid;
                if (tm.isActive(xid)) {
                    if (!logCache.containsKey(xid)) {
                        logCache.put(xid, new ArrayList<>());
                    }
                    logCache.get(xid).add(log);
                }
            }
        }

        // 对所有active log进行倒序undo
        for (Entry<Long, List<byte[]>> entry : logCache.entrySet()) {
            List<byte[]> logs = entry.getValue();
            for (int i = logs.size() - 1; i >= 0; i--) {
                byte[] log = logs.get(i);
                if (isInsertLog(log)) {
                    doInsertLog(pc, log, UNDO);
                } else {
                    doUpdateLog(pc, log, UNDO);
                }
            }
            tm.abort(entry.getKey());
        }
    }

    private static boolean isInsertLog(byte[] log) {
        return log[0] == LOG_TYPE_INSERT;
    }

    // Update Log 的格式：[LogType] [XID] [UID] [OldRaw] [NewRaw]
    private static final int OF_TYPE = 0;
    private static final int OF_XID = OF_TYPE + 1;
    private static final int OF_UPDATE_UID = OF_XID + 8;
    private static final int OF_UPDATE_RAW = OF_UPDATE_UID + 8;

    public static byte[] updateLog(long xid, DataItem di) {
        byte[] logType = { LOG_TYPE_UPDATE };
        byte[] xidRaw = Parser.long2Byte(xid);
        byte[] uidRaw = Parser.long2Byte(di.getUid());
        byte[] oldRaw = di.getOldRaw();
        SubArray raw = di.getRaw();
        byte[] newRaw = Arrays.copyOfRange(raw.raw, raw.start, raw.end);
        return Bytes.concat(logType, xidRaw, uidRaw, oldRaw, newRaw);
    }

    private static UpdateLogInfo parseUpdateLog(byte[] log) {
        UpdateLogInfo li = new UpdateLogInfo();
        li.xid = Parser.parseLong(Arrays.copyOfRange(log, OF_XID, OF_UPDATE_UID));
        long uid = Parser.parseLong(Arrays.copyOfRange(log, OF_UPDATE_UID, OF_UPDATE_RAW));
        li.offset = (short) (uid & ((1L << 16) - 1));
        uid >>>= 32;
        li.pgno = (int) (uid & ((1L << 32) - 1));
        int length = (log.length - OF_UPDATE_RAW) / 2;
        li.oldRaw = Arrays.copyOfRange(log, OF_UPDATE_RAW, OF_UPDATE_RAW + length);
        li.newRaw = Arrays.copyOfRange(log, OF_UPDATE_RAW + length, OF_UPDATE_RAW + length * 2);
        return li;
    }

    private static void doUpdateLog(PageCache pc, byte[] log, int flag) {
        int pgno;
        short offset;
        byte[] raw;
        if (flag == REDO) {
            UpdateLogInfo xi = parseUpdateLog(log);
            pgno = xi.pgno;
            offset = xi.offset;
            raw = xi.newRaw;
        } else {
            UpdateLogInfo xi = parseUpdateLog(log);
            pgno = xi.pgno;
            offset = xi.offset;
            raw = xi.oldRaw;
        }
        Page pg = null;
        try {
            pg = pc.getPage(pgno);
        } catch (Exception e) {
            Panic.panic(e);
        }
        try {
            PageX.recoverUpdate(pg, raw, offset);
        } finally {
            pg.release();
        }
    }

    // Insert Log 的格式：[LogType] [XID] [Pgno] [Offset] [Raw]
    private static final int OF_INSERT_PGNO = OF_XID + 8;
    private static final int OF_INSERT_OFFSET = OF_INSERT_PGNO + 4;
    private static final int OF_INSERT_RAW = OF_INSERT_OFFSET + 2;

    public static byte[] insertLog(long xid, Page pg, byte[] raw) {
        byte[] logTypeRaw = { LOG_TYPE_INSERT };
        byte[] xidRaw = Parser.long2Byte(xid);
        byte[] pgnoRaw = Parser.int2Byte(pg.getPageNumber());
        byte[] offsetRaw = Parser.short2Byte(PageX.getFSO(pg));
        return Bytes.concat(logTypeRaw, xidRaw, pgnoRaw, offsetRaw, raw);
    }

    private static InsertLogInfo parseInsertLog(byte[] log) {
        InsertLogInfo li = new InsertLogInfo();
        li.xid = Parser.parseLong(Arrays.copyOfRange(log, OF_XID, OF_INSERT_PGNO));
        li.pgno = Parser.parseInt(Arrays.copyOfRange(log, OF_INSERT_PGNO, OF_INSERT_OFFSET));
        li.offset = Parser.parseShort(Arrays.copyOfRange(log, OF_INSERT_OFFSET, OF_INSERT_RAW));
        li.raw = Arrays.copyOfRange(log, OF_INSERT_RAW, log.length);
        return li;
    }

    private static void doInsertLog(PageCache pc, byte[] log, int flag) {
        InsertLogInfo li = parseInsertLog(log);
        Page pg = null;
        try {
            pg = pc.getPage(li.pgno);
        } catch (Exception e) {
            Panic.panic(e);
        }
        try {
            /**
             * 如果是REDO，则直接重放，如果是UNDO，
             * 则先将raw标记为无效，再重放
             */
            if (flag == UNDO) {
                DataItem.setDataItemRawInvalid(li.raw);
            }
            PageX.recoverInsert(pg, li.raw, li.offset);
        } finally {
            pg.release();
        }
    }
}
