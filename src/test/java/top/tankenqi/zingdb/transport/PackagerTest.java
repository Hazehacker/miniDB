package top.tankenqi.zingdb.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public class PackagerTest {

    /**
     * 端到端往返：客户端发 REQUEST，服务端回 RESULT_SET / OK / ERROR；
     * 全部用二进制帧 + 新 Encoder。
     */
    @Test
    public void testPackager() throws Exception {
        int port = pickPort();
        CountDownLatch ready = new CountDownLatch(1);
        Throwable[] serverErr = new Throwable[1];

        Thread serverThread = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                ready.countDown();
                try (Socket sock = ss.accept()) {
                    Packager p = new Packager(new Transporter(sock), new Encoder());

                    Package req1 = p.receive();
                    assertEquals(Package.TYPE_REQUEST, req1.getType());
                    assertEquals("select * from users", req1.getSql());

                    ResultSet rs = new ResultSet(
                        new String[]{"id", "name", "age"},
                        new byte[]{ColumnType.INT32, ColumnType.STRING, ColumnType.INT32});
                    rs.addRow(new Object[]{1, "alice", 23});
                    rs.addRow(new Object[]{2, "bob", null});
                    rs.setNote("index scan on age");
                    Package resp = Package.resultSet(rs);
                    resp.setElapsedNanos(12_400_000L);
                    p.send(resp);

                    Package req2 = p.receive();
                    assertEquals("commit", req2.getSql());
                    Package ok = Package.ok("commit", 0);
                    ok.setElapsedNanos(1_000_000L);
                    p.send(ok);

                    Package req3 = p.receive();
                    assertEquals("oops", req3.getSql());
                    p.send(Package.error("PR-0001", "Invalid command!"));
                }
            } catch (Throwable t) {
                serverErr[0] = t;
            }
        }, "packager-test-server");
        serverThread.start();
        ready.await();

        try (Socket sock = new Socket("127.0.0.1", port)) {
            Packager p = new Packager(new Transporter(sock), new Encoder());

            p.send(Package.request("select * from users"));
            Package rsPkg = p.receive();
            assertTrue(rsPkg.isResultSet());
            ResultSet rs = rsPkg.getResultSet();
            assertEquals(3, rs.columnCount());
            assertEquals(2, rs.rowCount());
            assertArrayEquals(new String[]{"id", "name", "age"}, rs.getColumnNames());
            List<Object[]> rows = rs.getRows();
            assertEquals(1, ((Integer) rows.get(0)[0]).intValue());
            assertEquals("alice", rows.get(0)[1]);
            assertEquals(23, ((Integer) rows.get(0)[2]).intValue());
            assertNull(rows.get(1)[2]);
            assertEquals("index scan on age", rs.getNote());
            assertEquals(12_400_000L, rsPkg.getElapsedNanos());

            p.send(Package.request("commit"));
            Package okPkg = p.receive();
            assertTrue(okPkg.isOk());
            assertEquals("commit", okPkg.getMessage());

            p.send(Package.request("oops"));
            Package errPkg = p.receive();
            assertTrue(errPkg.isError());
            assertEquals("PR-0001", errPkg.getErrCode());
            assertNotNull(errPkg.getMessage());
        }

        serverThread.join(5_000);
        if (serverErr[0] != null) {
            throw new AssertionError("server thread failed", serverErr[0]);
        }
    }

    private static int pickPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
