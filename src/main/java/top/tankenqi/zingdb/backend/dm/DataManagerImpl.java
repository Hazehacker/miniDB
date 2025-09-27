package top.tankenqi.zingdb.backend.dm;

import top.tankenqi.zingdb.backend.common.AbstractCache;
import top.tankenqi.zingdb.backend.dm.dataItem.DataItem;
import top.tankenqi.zingdb.backend.dm.dataItem.DataItemImpl;
import top.tankenqi.zingdb.backend.dm.logger.Logger;
import top.tankenqi.zingdb.backend.dm.page.Page;
import top.tankenqi.zingdb.backend.dm.page.PageOne;
import top.tankenqi.zingdb.backend.dm.page.PageX;
import top.tankenqi.zingdb.backend.dm.pageCache.PageCache;
import top.tankenqi.zingdb.backend.dm.pageIndex.PageIndex;
import top.tankenqi.zingdb.backend.dm.pageIndex.PageInfo;
import top.tankenqi.zingdb.backend.tm.TransactionManager;
import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.backend.utils.Types;
import top.tankenqi.zingdb.common.Error;

public class DataManagerImpl extends AbstractCache<DataItem> implements DataManager {

    TransactionManager tm;
    PageCache pc;
    Logger logger;
    PageIndex pIndex;
    Page pageOne;

    public DataManagerImpl(PageCache pc, Logger logger, TransactionManager tm) {
        super(0);
        this.pc = pc;
        this.logger = logger;
        this.tm = tm;
        this.pIndex = new PageIndex();
    }

    @Override
    public DataItem read(long uid) throws Exception {
        DataItemImpl di = (DataItemImpl) super.get(uid);
        if (!di.isValid()) {
            di.release();
            return null;
        }
        return di;
    }

    /**
     * 先尝试从 PageIndex 找能容纳数据的页
     * 找不到就创建新页，并加入索引
     * 插入数据后，重新计算该页剩余空间，更新索引
     * 这样避免了遍历所有页来寻找合适空间，提高了插入效率
     */
    @Override
    public long insert(long xid, byte[] data) throws Exception {
        // 将原始数据包装成 DataItem 格式
        byte[] raw = DataItem.wrapDataItemRaw(data);
        // 检查数据是否超过单页最大容量（8KB - 2字节的 FSO 字段）
        if (raw.length > PageX.MAX_FREE_SPACE) {
            throw Error.DataTooLargeException;
        }

        // 从 PageIndex 找能容纳数据的现有页，最多尝试5次，避免并发竞争导致的失败
        PageInfo pi = null;
        for (int i = 0; i < 5; i++) {
            pi = pIndex.select(raw.length);
            if (pi != null) {
                break;
            } else {
                // 如果找不到能容纳数据的现有页，则创建新页，并加入索引
                int newPgno = pc.newPage(PageX.initRaw());
                pIndex.add(newPgno, PageX.MAX_FREE_SPACE);
            }
        }
        if (pi == null) {
            throw Error.DatabaseBusyException;
        }

        Page pg = null;
        int freeSpace = 0;
        try {
            pg = pc.getPage(pi.pgno); // 从缓存获取页，并使得pg的引用+1
            // 记录日志
            byte[] log = Recover.insertLog(xid, pg, raw);
            logger.log(log);

            // 插入数据
            short offset = PageX.insert(pg, raw);

            // pg 使用完毕，引用-1
            pg.release();

            // 将页号和偏移组合成 uid 返回给上层
            return Types.addressToUid(pi.pgno, offset);

        } finally {
            // 无论插入成功与否，都要更新 PageIndex 中该页的空闲空间信息
            if (pg != null) {
                pIndex.add(pi.pgno, PageX.getFreeSpace(pg));
            } else {
                pIndex.add(pi.pgno, freeSpace);
            }
        }
    }

    @Override
    public void close() {
        super.close();
        logger.close();
        // 不要忘了设置第一页的字节校验字段
        PageOne.setVcClose(pageOne);
        pageOne.release();
        pc.close();
    }

    // 为xid生成update日志
    public void logDataItem(long xid, DataItem di) {
        byte[] log = Recover.updateLog(xid, di);
        logger.log(log);
    }

    public void releaseDataItem(DataItem di) {
        super.release(di.getUid());
    }

    /**
     * DataManager 是 DM 层直接对外提供方法的类，同时，
     * 也实现成 DataItem 对象的缓存。DataItem 存储的 key，
     * 是由页号和页内偏移组成的一个 8 字节无符号整数，页号和偏移各占 4 字节
     * 
     * DataItem 缓存，getForCache()，只需要从 key 中解析出页号，
     * 从 pageCache 中获取到页面，再根据偏移，解析出 DataItem 即可
     */
    @Override
    protected DataItem getForCache(long uid) throws Exception {
        short offset = (short) (uid & ((1L << 16) - 1));
        uid >>>= 32;
        int pgno = (int) (uid & ((1L << 32) - 1));
        Page pg = pc.getPage(pgno);
        return DataItem.parseDataItem(pg, offset, this);
    }

    /**
     * 只需要将 DataItem 所在的页 release 即可
     */
    @Override
    protected void releaseForCache(DataItem di) {
        di.page().release();
    }

    // 在创建文件时初始化PageOne
    void initPageOne() {
        int pgno = pc.newPage(PageOne.InitRaw());
        assert pgno == 1;
        try {
            pageOne = pc.getPage(pgno);
        } catch (Exception e) {
            Panic.panic(e);
        }
        pc.flushPage(pageOne);
    }

    // 在打开已有文件时时读入PageOne，并验证正确性
    boolean loadCheckPageOne() {
        try {
            pageOne = pc.getPage(1);
        } catch (Exception e) {
            Panic.panic(e);
        }
        return PageOne.checkVc(pageOne);
    }

    // 初始化 PageIndex，需要获取所有页面并填充 PageIndex
    void fillPageIndex() {
        // 这个pageNumber是打开的数据库文件的页面数量
        int pageNumber = pc.getPageNumber();
        // 从2开始，因为1是pageOne
        for (int i = 2; i <= pageNumber; i++) {
            Page pg = null;
            try {
                // 这里会使得pg的引用+1
                pg = pc.getPage(i);
            } catch (Exception e) {
                Panic.panic(e);
            }
            // 这个pageNumber是页号，不是数据库文件的页面数量
            pIndex.add(pg.getPageNumber(), PageX.getFreeSpace(pg));
            /**
             * 这里会使得pg的引用-1，
             * 这里只是读取页的空闲空间信息，不需要长期持有，
             * 所以用完就 release，让缓存能正常管理内存
             */
            pg.release();
        }
    }

}
