package top.tankenqi.zingdb.backend.tm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.common.Error;

public interface TransactionManager {
    long begin(); // 开始一个事务，并返回XID

    void commit(long xid); // 提交一个事务

    void abort(long xid); // 回滚一个事务

    boolean isActive(long xid); // 检查一个事务是否已开始

    boolean isCommitted(long xid); // 检查一个事务是否已提交

    boolean isAborted(long xid); // 检查一个事务是否已回滚

    void close(); // 关闭事务管理器

    /**
     * 创建一个 xid 文件并创建 TM
     *
     * @param path
     * @return
     */
    public static TransactionManagerImpl create(String path) {
        File f = new File(path + TransactionManagerImpl.XID_SUFFIX);
        try {
            if (!f.createNewFile()) {
                Panic.panic(Error.FileExistsException);
            }
        } catch (Exception e) {
            Panic.panic(e);
        }
        if (!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }

        FileChannel fc = null;
        RandomAccessFile raf = null;

        try {
            raf = new RandomAccessFile(f, "rw");
            fc = raf.getChannel();
        } catch (FileNotFoundException e) {
            Panic.panic(e);
        }

        // 初始化 XID 文件头，内容是全0
        ByteBuffer buf = ByteBuffer.wrap(new byte[TransactionManagerImpl.LEN_XID_HEADER_LENGTH]);
        try {
            fc.position(0);
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }

        return new TransactionManagerImpl(raf, fc);
    }

    /**
     * 从一个已有的 xid 文件来创建 TM
     *
     * @param path
     * @return
     */
    public static TransactionManagerImpl open(String path) {
        File f = new File(path + TransactionManagerImpl.XID_SUFFIX);
        if (!f.exists()) {
            Panic.panic(Error.FileNotExistsException);
        }
        if (!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }

        FileChannel fc = null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            fc = raf.getChannel();
        } catch (FileNotFoundException e) {
            Panic.panic(e);
        }

        return new TransactionManagerImpl(raf, fc);
    }
}
