package top.tankenqi.zingdb.backend.tm;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.backend.utils.Parser;
import top.tankenqi.zingdb.common.Error;

public class TransactionManagerImpl implements TransactionManager {

    // XID文件头长度（字节）
    static final int LEN_XID_HEADER_LENGTH = 8;
    // 每个事务的占用长度（字节）
    private static final int XID_FIELD_SIZE = 1;

    // 事务的三种状态（活跃、提交、回滚）
    private static final byte FIELD_TRAN_ACTIVE = 0;
    private static final byte FIELD_TRAN_COMMITTED = 1;
    private static final byte FIELD_TRAN_ABORTED = 2;

    // 超级事务的XID定义为“0”，永远为commited状态
    public static final long SUPER_XID = 0;

    // XID文件后缀
    static final String XID_SUFFIX = ".xid";

    private RandomAccessFile file;
    private FileChannel fc;
    private long xidCounter;
    private Lock counterLock;

    TransactionManagerImpl(RandomAccessFile raf, FileChannel fc) {
        this.file = raf;
        this.fc = fc;
        counterLock = new ReentrantLock();
        // 检查XID文件是否合法
        checkXIDCounter();
    }

    /**
     * 检查XID文件是否合法
     * 通过文件头信息反推文件的理论长度，与文件的实际长度做对比,
     * 如果不同则认为 XID 文件不合法。
     */
    private void checkXIDCounter() {
        long fileLen = 0;
        try {
            fileLen = file.length();
        } catch (IOException e1) {
            Panic.panic(Error.BadXIDFileException);
        }

        // 文件长度至少要能容纳 XID_Header
        if (fileLen < LEN_XID_HEADER_LENGTH) {
            Panic.panic(Error.BadXIDFileException);
        }

        ByteBuffer buf = ByteBuffer.allocate(LEN_XID_HEADER_LENGTH);
        try {
            fc.position(0);
            fc.read(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        // 根据文件头信息，计算出XID的数量
        this.xidCounter = Parser.parseLong(buf.array());
        // 计算理论上的文件长度
        long end = getXidPosition(this.xidCounter + 1);
        if (end != fileLen) {
            Panic.panic(Error.BadXIDFileException);
        }
    }

    // 根据事务xid取得其在xid文件中对应的位置
    private long getXidPosition(long xid) {
        return LEN_XID_HEADER_LENGTH + (xid - 1) * XID_FIELD_SIZE;
    }

    // 更新xid事务的状态为status
    private void updateXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        byte[] tmp = new byte[XID_FIELD_SIZE];
        tmp[0] = status;
        ByteBuffer buf = ByteBuffer.wrap(tmp);

        try {
            fc.position(offset);
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            fc.force(false); // 强制将数据写入磁盘
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    // 将XID加一，并更新XID Header
    private void incrXIDCounter() {
        xidCounter++;
        ByteBuffer buf = ByteBuffer.wrap(Parser.long2Byte(xidCounter));
        try {
            fc.position(0);
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            fc.force(false); // 强制将数据写入磁盘
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    /**
     * begin() 方法会开始一个事务，更具体的，
     * 首先设置 xidCounter+1 事务的状态为 active，
     * 随后 xidCounter 自增，并更新文件头。
     */
    public long begin() {
        /**
         * 加锁，防止多个线程同时访问 xidCounter，counterLock是ReentrantLock
         * 但是不能避免多进程的冲突，这里可以选择使用文件锁或者分布式锁来解决这个问题
         */
        counterLock.lock();
        try {
            // 获取新的XID，就是当前的 xidCounter + 1
            long xid = xidCounter + 1;
            // 设置新的XID的状态为 active
            updateXID(xid, FIELD_TRAN_ACTIVE);
            // 更新 xidCounter
            incrXIDCounter();
            return xid;
        } finally {
            counterLock.unlock();
        }
    }

    // 提交XID事务
    public void commit(long xid) {
        updateXID(xid, FIELD_TRAN_COMMITTED);
    }

    // 回滚XID事务
    public void abort(long xid) {
        updateXID(xid, FIELD_TRAN_ABORTED);
    }

    // 检测XID事务是否处于status状态
    private boolean checkXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        ByteBuffer buf = ByteBuffer.wrap(new byte[XID_FIELD_SIZE]);
        try {
            fc.position(offset);
            fc.read(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        return buf.array()[0] == status;
    }

    public boolean isActive(long xid) {
        if (xid == SUPER_XID)
            return false;
        return checkXID(xid, FIELD_TRAN_ACTIVE);
    }

    public boolean isCommitted(long xid) {
        if (xid == SUPER_XID)
            return true;
        return checkXID(xid, FIELD_TRAN_COMMITTED);
    }

    public boolean isAborted(long xid) {
        if (xid == SUPER_XID)
            return false;
        return checkXID(xid, FIELD_TRAN_ABORTED);
    }

    /**
     * 关闭事务管理器
     */
    public void close() {
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

}
