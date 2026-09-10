package top.zhongnan.minidb.client;

import top.zhongnan.minidb.transport.Package;
import top.zhongnan.minidb.transport.Packager;

public class RoundTripper {
    private final Packager packager;

    public RoundTripper(Packager packager) {
        this.packager = packager;
    }

    public Package roundTrip(Package pkg) throws Exception {
        packager.send(pkg);
        return packager.receive();
    }

    public void close() throws Exception {
        packager.close();
    }
}
