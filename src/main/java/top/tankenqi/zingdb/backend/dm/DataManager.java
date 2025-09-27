package top.tankenqi.zingdb.backend.dm;

import top.tankenqi.zingdb.backend.dm.dataItem.DataItem;
import top.tankenqi.zingdb.backend.dm.logger.Logger;
import top.tankenqi.zingdb.backend.dm.page.PageOne;
import top.tankenqi.zingdb.backend.dm.pageCache.PageCache;
import top.tankenqi.zingdb.backend.tm.TransactionManager;

public interface DataManager {
    DataItem read(long uid) throws Exception;
    long insert(long xid, byte[] data) throws Exception;
    void close();

    public static DataManager create(String path, long mem, TransactionManager tm) {
        PageCache pc = PageCache.create(path, mem);
        Logger lg = Logger.create(path);
        DataManagerImpl dm = new DataManagerImpl(pc, lg, tm);
        dm.initPageOne(); // 初始化pageOne
        return dm;
    }

    public static DataManager open(String path, long mem, TransactionManager tm) {
        PageCache pc = PageCache.open(path, mem);
        Logger lg = Logger.open(path);
        DataManagerImpl dm = new DataManagerImpl(pc, lg, tm);
        if (!dm.loadCheckPageOne()) { // 检查pageOne是否正确
            /**
             * 如果不正确，说明上次数据库关闭时没有正常关闭，
             * 数据没有正常落盘，需要从日志进行恢复
             */
            Recover.recover(tm, lg, pc);
        }
        dm.fillPageIndex();
        PageOne.setVcOpen(dm.pageOne);
        dm.pc.flushPage(dm.pageOne);

        return dm;
    }
}
