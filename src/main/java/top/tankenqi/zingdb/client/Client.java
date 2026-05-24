package top.tankenqi.zingdb.client;

import top.tankenqi.zingdb.transport.Package;
import top.tankenqi.zingdb.transport.Packager;

/**
 * 客户端入口：发送一条 SQL，返回服务端的 Package（OK / RESULT_SET / ERROR）。
 *
 * 错误不再以异常形式抛出，而是直接返回 ERROR Package，
 * 由 Shell 决定如何渲染（颜色、错误码、是否切换提示符状态）。
 */
public class Client {
    private final RoundTripper rt;

    public Client(Packager packager) {
        this.rt = new RoundTripper(packager);
    }

    public Package execute(String sql) throws Exception {
        return rt.roundTrip(Package.request(sql));
    }

    public void close() {
        try { rt.close(); } catch (Exception ignored) {}
    }
}
