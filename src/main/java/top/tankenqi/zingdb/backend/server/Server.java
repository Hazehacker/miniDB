package top.tankenqi.zingdb.backend.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import top.tankenqi.zingdb.backend.tbm.TableManager;
import top.tankenqi.zingdb.transport.Encoder;
import top.tankenqi.zingdb.transport.Package;
import top.tankenqi.zingdb.transport.Packager;
import top.tankenqi.zingdb.transport.Transporter;

/**
 * 监听端口、接受连接、把请求交给线程池处理。
 *
 * 与旧实现的差异：
 *   - 通信走新二进制协议（Packager + 新 Encoder）；
 *   - 输出统一改为 SLF4J；
 *   - 注册 shutdown hook 优雅关闭：停止 accept、shutdown 线程池、关闭 ServerSocket。
 */
public class Server {

    private static final Logger log = LoggerFactory.getLogger(Server.class);

    private final int port;
    private final TableManager tbm;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile ThreadPoolExecutor pool;

    public Server(int port, TableManager tbm) {
        this.port = port;
        this.tbm = tbm;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            log.error("failed to listen on port {}", port, e);
            return;
        }
        running.set(true);
        pool = new ThreadPoolExecutor(
            10, 20, 1L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy());

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "zingdb-shutdown"));

        log.info("ZingDB server listening on port {}", port);
        try {
            while (running.get()) {
                Socket socket;
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    if (!running.get()) break;          // 被 shutdown 时 accept 会抛出
                    log.warn("accept failed", e);
                    continue;
                }
                pool.execute(new HandleSocket(socket, tbm));
            }
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        log.info("shutting down ZingDB server...");
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        log.info("ZingDB server stopped.");
    }
}

/**
 * 单个客户端连接的处理循环。
 */
class HandleSocket implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(HandleSocket.class);

    private final Socket socket;
    private final TableManager tbm;

    HandleSocket(Socket socket, TableManager tbm) {
        this.socket = socket;
        this.tbm = tbm;
    }

    @Override
    public void run() {
        InetSocketAddress addr = (InetSocketAddress) socket.getRemoteSocketAddress();
        log.info("client connected: {}:{}", addr.getAddress().getHostAddress(), addr.getPort());
        ServerMetrics.get().onConnect();
        Packager packager;
        try {
            packager = new Packager(new Transporter(socket), new Encoder());
        } catch (IOException e) {
            log.warn("failed to set up packager for {}", addr, e);
            try { socket.close(); } catch (IOException ignored) {}
            ServerMetrics.get().onDisconnect();
            return;
        }

        Executor exe = new Executor(tbm);
        try {
            while (true) {
                Package req;
                try {
                    req = packager.receive();
                } catch (Exception e) {
                    // 连接断开是正常退出路径
                    break;
                }
                if (req.getType() != Package.TYPE_REQUEST) {
                    try { packager.send(Package.error("TP-0002", "expected REQUEST frame")); }
                    catch (Exception ignore) {}
                    continue;
                }
                String sql = req.getSql();
                log.debug("execute [xid={}]: {}", exe.inTransaction(), sql);
                Package resp = exe.execute(sql);
                try {
                    packager.send(resp);
                } catch (Exception e) {
                    log.warn("send response failed", e);
                    break;
                }
            }
        } finally {
            exe.close();
            try { packager.close(); } catch (IOException ignored) {}
            ServerMetrics.get().onDisconnect();
            log.info("client disconnected: {}", addr);
        }
    }
}
