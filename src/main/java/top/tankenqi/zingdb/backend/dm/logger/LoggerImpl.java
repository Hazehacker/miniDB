package top.tankenqi.zingdb.backend.dm.logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.primitives.Bytes;

import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.backend.utils.Parser;
import top.tankenqi.zingdb.common.Error;

/**
 * 日志文件读写
 * 
 * 日志文件标准格式为：
 * [XChecksum] [Log1] [Log2] ... [LogN] [BadTail]
 * XChecksum 为后续所有日志计算的Checksum，int类型
 * 
 * 每条正确日志的格式为：
 * [Size] [Checksum] [Data]
 * Size 4字节 int 标识 Data 长度
 * Checksum 4字节 int
 */
public class LoggerImpl implements Logger {

    private static final int SEED = 13331;

    /**
     * 每条日志的格式如下：
     * [Size][Checksum][Data]
     */
    private static final int OF_SIZE = 0;
    private static final int OF_CHECKSUM = OF_SIZE + 4;
    private static final int OF_DATA = OF_CHECKSUM + 4;

    public static final String LOG_SUFFIX = ".log";

    private RandomAccessFile file;
    private FileChannel fc;
    private Lock lock;

    private long position; // 当前日志指针的位置
    private long fileSize; // 初始化时记录，log操作不更新
    private int xChecksum;

    LoggerImpl(RandomAccessFile raf, FileChannel fc) {
        this.file = raf;
        this.fc = fc;
        lock = new ReentrantLock();
    }

    LoggerImpl(RandomAccessFile raf, FileChannel fc, int xChecksum) {
        this.file = raf;
        this.fc = fc;
        this.xChecksum = xChecksum;
        lock = new ReentrantLock();
    }

    /**
     * 这个初始化是对打开已有的日志文件的初始化，而不是创建新的日志文件的初始化
     */
    void init() {
        long size = 0;
        try {
            size = file.length();
        } catch (IOException e) {
            Panic.panic(e);
        }
        if (size < 4) { // 日志文件长度至少要能容纳 XChecksum（4字节）
            Panic.panic(Error.BadLogFileException);
        }

        ByteBuffer raw = ByteBuffer.allocate(4);
        try {
            fc.position(0);
            fc.read(raw);
        } catch (IOException e) {
            Panic.panic(e);
        }
        int xChecksum = Parser.parseInt(raw.array());
        this.fileSize = size;
        this.xChecksum = xChecksum;

        checkAndRemoveTail();
    }

    // 检查并移除bad tail，bad tail 是在数据库崩溃时，没有来得及写完的日志数据
    private void checkAndRemoveTail() {
        // 将指针重置到日志文件的开始，即XChecksum位置后面
        rewind();

        int xCheck = 0;
        // 重新计算一遍 xChecksum
        while (true) {
            byte[] log = internNext(); // 读取下一个日志
            if (log == null)
                break;
            xCheck = calChecksum(xCheck, log);
        }
        /**
         * 如果计算出的xChecksum与文件头中的xChecksum不一致，
         * 则认为日志文件损坏，这里并没有用到坏尾做计算
         */
        if (xCheck != xChecksum) {
            Panic.panic(Error.BadLogFileException);
        }

        /**
         * 截断日志文件到正常日志的末尾，这样就把坏尾给截断了
         */
        try {
            truncate(position);
        } catch (Exception e) {
            Panic.panic(e);
        }

        /**
         * 把文件写指针移动到 position，后续追加的新日志就会从这里继续写入，
         * 与截断配合，先删掉坏尾，再把写游标放到正确位置，
         * 保证新日志不会接在损坏数据后面
         */
        try {
            file.seek(position);
        } catch (IOException e) {
            Panic.panic(e);
        }

        // 将指针重置到日志文件的开始，即XChecksum位置后面
        rewind();
    }

    private int calChecksum(int xCheck, byte[] log) {
        for (byte b : log) {
            xCheck = xCheck * SEED + b;
        }
        return xCheck;
    }

    /**
     * 向日志文件写入日志时，也是首先将数据包裹成日志格式，
     * 写入文件后，再更新文件的校验和，更新校验和时，
     * 会刷新缓冲区，保证内容写入磁盘
     */
    @Override
    public void log(byte[] data) {
        // 将数据包裹成日志格式
        byte[] log = wrapLog(data);
        ByteBuffer buf = ByteBuffer.wrap(log);
        lock.lock();
        try {
            fc.position(fc.size());
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        } finally {
            lock.unlock();
        }
        // 更新文件的校验和
        updateXChecksum(log);
    }

    private void updateXChecksum(byte[] log) {
        this.xChecksum = calChecksum(this.xChecksum, log);
        try {
            fc.position(0);
            fc.write(ByteBuffer.wrap(Parser.int2Byte(xChecksum)));
            fc.force(false); // 强制将数据写入磁盘
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    private byte[] wrapLog(byte[] data) {
        byte[] checksum = Parser.int2Byte(calChecksum(0, data));
        byte[] size = Parser.int2Byte(data.length);
        return Bytes.concat(size, checksum, data);
    }

    @Override
    public void truncate(long x) throws Exception {
        lock.lock();
        try {
            fc.truncate(x);
        } finally {
            lock.unlock();
        }
    }

    private byte[] internNext() {
        if (position + OF_DATA >= fileSize) {
            return null;
        }
        ByteBuffer tmp = ByteBuffer.allocate(4);
        try {
            fc.position(position);
            fc.read(tmp);
        } catch (IOException e) {
            Panic.panic(e);
        }
        int size = Parser.parseInt(tmp.array());
        if (position + size + OF_DATA > fileSize) {
            return null;
        }

        // 读取当前这条日志数据，是一个：[Size][Checksum][Data]的结构
        ByteBuffer buf = ByteBuffer.allocate(OF_DATA + size);
        try {
            fc.position(position);
            fc.read(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }

        byte[] log = buf.array();
        // 将[Data]部分拷贝出来，并用[Data]来重新计算当前这条日志数据的Checksum
        int checkSum1 = calChecksum(0, Arrays.copyOfRange(log, OF_DATA, log.length));
        // 读取当前这条日志数据的Checksum
        int checkSum2 = Parser.parseInt(Arrays.copyOfRange(log, OF_CHECKSUM, OF_DATA));
        // 如果计算出的Checksum与读取的Checksum不一致，则认为日志数据损坏
        if (checkSum1 != checkSum2) {
            return null;
        }
        position += log.length;
        return log;
    }

    @Override
    public byte[] next() {
        lock.lock();
        try {
            byte[] log = internNext();
            if (log == null)
                return null;
            // 返回[Size][Checksum][Data]这条日志中的[Data]部分
            return Arrays.copyOfRange(log, OF_DATA, log.length);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void rewind() {
        position = 4;
    }

    @Override
    public void close() {
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

}
