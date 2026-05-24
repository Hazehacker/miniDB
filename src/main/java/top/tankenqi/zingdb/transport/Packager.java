package top.tankenqi.zingdb.transport;

import java.io.IOException;
import java.util.Arrays;

/**
 * 把 Transporter 的字节帧与 Encoder 的逻辑 Package 串起来。
 *
 * 上层（Server/Client）只感知 Package，不直接接触字节。
 */
public class Packager {
    private final Transporter transporter;
    private final Encoder encoder;

    public Packager(Transporter transporter, Encoder encoder) {
        this.transporter = transporter;
        this.encoder = encoder;
    }

    public void send(Package pkg) throws Exception {
        byte[] payload = encoder.encode(pkg);
        transporter.send(pkg.getType(), payload);
    }

    public Package receive() throws Exception {
        byte[] raw = transporter.receive();
        byte type = raw[0];
        byte[] payload = Arrays.copyOfRange(raw, 1, raw.length);
        return encoder.decode(type, payload);
    }

    public void close() throws IOException {
        transporter.close();
    }
}
