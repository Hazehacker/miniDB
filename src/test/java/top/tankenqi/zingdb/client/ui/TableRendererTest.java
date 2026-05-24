package top.tankenqi.zingdb.client.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import top.tankenqi.zingdb.transport.ColumnType;
import top.tankenqi.zingdb.transport.Package;
import top.tankenqi.zingdb.transport.ResultSet;

/**
 * 表格渲染的烟雾测试。关掉颜色 + Unicode，对纯文本做行/字符断言，
 * 同时验证 ResultSet/OK/ERROR 三类 Package 都能渲染不抛异常。
 */
public class TableRendererTest {

    @Before
    public void setUp() {
        Ansi.setEnabled(false);
        Theme.setUnicode(false);
    }

    @After
    public void tearDown() {
        Ansi.setEnabled(true);
        Theme.setUnicode(true);
    }

    @Test
    public void renderResultSetBasic() {
        ResultSet rs = new ResultSet(
            new String[]{"id", "name", "age"},
            new byte[]{ColumnType.INT32, ColumnType.STRING, ColumnType.INT32});
        rs.addRow(new Object[]{1, "alice", 23});
        rs.addRow(new Object[]{2, "bob",   null});
        Package pkg = Package.resultSet(rs);
        pkg.setElapsedNanos(12_400_000L);

        String out = TableRenderer.render(pkg);
        assertTrue("must contain headers", out.contains("id") && out.contains("name") && out.contains("age"));
        assertTrue("must contain rows", out.contains("alice") && out.contains("bob"));
        assertTrue("null cell visible (ASCII fallback to symbol)", out.contains("∅") || out.contains("NULL"));
        assertTrue("footer has rows count", out.contains("2 rows"));
        assertTrue("footer has elapsed", out.contains("ms"));
        // 应该有 ASCII 表格边框
        assertTrue("contains ASCII border", out.contains("+") && out.contains("|"));
    }

    @Test
    public void renderOk() {
        Package p = Package.ok("commit", 0);
        p.setElapsedNanos(1_000_000L);
        String out = TableRenderer.render(p);
        assertTrue(out.contains("commit"));
        assertTrue(out.contains("ms"));
        assertFalse(out.contains("|"));   // OK 不是表格
    }

    @Test
    public void renderError() {
        Package p = Package.error("PR-0001", "Invalid command!");
        String out = TableRenderer.render(p);
        assertTrue(out.contains("ERROR"));
        assertTrue(out.contains("PR-0001"));
        assertTrue(out.contains("Invalid command!"));
    }

    @Test
    public void truncateLongCell() {
        ResultSet rs = new ResultSet(new String[]{"v"}, new byte[]{ColumnType.STRING});
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append('x');
        rs.addRow(new Object[]{sb.toString()});
        Package pkg = Package.resultSet(rs);

        String out = TableRenderer.render(pkg);
        // 不应该爆行（超长应该被截断或宽列下显示）
        for (String line : out.split("\n")) {
            assertTrue("line too long: " + line.length(), line.length() < 200);
        }
    }
}
