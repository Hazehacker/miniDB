package top.zhongnan.minidb.cli;

import top.zhongnan.minidb.engine.net.Package;
import top.zhongnan.minidb.engine.net.Packager;

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
