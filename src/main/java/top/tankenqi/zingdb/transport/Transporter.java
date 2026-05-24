package top.tankenqi.zingdb.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

import top.tankenqi.zingdb.common.Error;

/**
 * 字节级帧传输。
 *
 * 帧格式：
 *   [magic1:1=0x5A][magic2:1=0x44][type:1][len:4][payload:len]
 *
 * 与上一版基于行（HEX + '\n'）相比的优势：
 *   - 避免每字节 2 倍膨胀；
 *   - 显式 magic 用于快速校验，错位时直接抛出而不是阻塞解析；
 *   - len 在头部明确，下游 Encoder 解析无需依赖换行。
 */
public class Transporter {

    private static final byte MAGIC_1 = 0x5A; // 'Z'
    private static final byte MAGIC_2 = 0x44; // 'D'

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public Transporter(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /**
     * 发送一帧。type 来自 Package.TYPE_*。
     */
    public synchronized void send(byte type, byte[] payload) throws IOException {
        out.writeByte(MAGIC_1);
        out.writeByte(MAGIC_2);
        out.writeByte(type);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /**
     * 接收一帧。返回 [type(1B), payload(...)] 拼接的数组：
     *   result[0] = type
     *   result[1..] = payload
     * 这种打包方式与 Packager 的 decode 接口最契合，避免多次返回值结构。
     */
    public byte[] receive() throws IOException {
        int b1, b2;
        try {
            b1 = in.readUnsignedByte();
            b2 = in.readUnsignedByte();
        } catch (EOFException eof) {
            throw eof;
        }
        if (b1 != (MAGIC_1 & 0xFF) || b2 != (MAGIC_2 & 0xFF)) {
            throw new IOException("bad frame magic: " + Integer.toHexString(b1) + " " + Integer.toHexString(b2));
        }
        byte type = in.readByte();
        int len = in.readInt();
        if (len < 0) throw new IOException("bad frame length: " + len);
        byte[] payload = new byte[len + 1];
        payload[0] = type;
        in.readFully(payload, 1, len);
        return payload;
    }

    public void close() throws IOException {
        try { out.close(); } catch (IOException ignored) {}
        try { in.close(); } catch (IOException ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }

    // 兼容 Error 中的 InvalidPkgDataException 引用路径（保持包内可见）
    @SuppressWarnings("unused")
    private static final Class<?> ERR_TYPE_HOLDER = Error.class;
}
