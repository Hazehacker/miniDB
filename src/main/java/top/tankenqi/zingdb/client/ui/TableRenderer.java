package top.tankenqi.zingdb.client.ui;

import java.util.List;

import top.tankenqi.zingdb.transport.ColumnType;
import top.tankenqi.zingdb.transport.Package;
import top.tankenqi.zingdb.transport.ResultSet;

/**
 * 漂亮的终端表格渲染。
 *
 * 输出风格（参考 psql / sqlite3 / claude-code）：
 *   ┌────┬─────────┬─────┐
 *   │ id │ name    │ age │   <- 表头粗体 + cyan
 *   ├────┼─────────┼─────┤
 *   │  1 │ alice   │  23 │   <- 数字右对齐 / 字符串左对齐 / NULL 灰显
 *   │  2 │ bob     │   ∅ │
 *   └────┴─────────┴─────┘
 *   2 rows  ·  index scan  ·  12.40 ms
 *
 * 自适应：
 *   - 列宽 = max(列名宽, 各行该列宽)
 *   - 总宽超过终端宽度时按比例缩窄，截断处显示 "…"
 *   - 颜色全部走 Theme / Ansi，--no-color 时自动退化为纯字符
 */
public final class TableRenderer {

    private static final int MIN_COL_WIDTH = 3;
    private static final int MAX_DEFAULT_COL_WIDTH = 60;

    private TableRenderer() {}

    public static String render(Package pkg) {
        if (pkg.isError()) {
            return Theme.errorTag("✗ ERROR")
                + " " + Theme.muted("[" + pkg.getErrCode() + "]")
                + " " + pkg.getMessage();
        }
        if (pkg.isOk()) {
            return renderOk(pkg);
        }
        if (pkg.isResultSet()) {
            ResultSet rs = pkg.getResultSet();
            String tbl = renderTable(rs);
            return tbl + "\n" + renderFooter(rs.rowCount(), pkg.getElapsedNanos(), rs.getNote());
        }
        return "(unknown response)";
    }

    // ---------- OK / footer ----------

    private static String renderOk(Package pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append(Theme.success("✓ "));
        String msg = pkg.getMessage();
        sb.append(msg == null ? "OK" : msg);
        long rows = pkg.getRowsAffected();
        if (rows >= 0) {
            sb.append("  ").append(Theme.muted(rowsText(rows)));
        }
        if (pkg.getElapsedNanos() > 0) {
            sb.append("  ").append(Theme.muted(formatElapsed(pkg.getElapsedNanos())));
        }
        return sb.toString();
    }

    private static String renderFooter(long rows, long elapsedNanos, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append(Theme.muted(rowsText(rows)));
        if (note != null && !note.isEmpty()) {
            sb.append(Theme.muted("  " + Theme.bullet() + "  " + note));
        }
        if (elapsedNanos > 0) {
            sb.append(Theme.muted("  " + Theme.bullet() + "  " + formatElapsed(elapsedNanos)));
        }
        return sb.toString();
    }

    private static String rowsText(long n) {
        return n + (n == 1 ? " row" : " rows");
    }

    private static String formatElapsed(long nanos) {
        if (nanos < 1_000) return nanos + " ns";
        if (nanos < 1_000_000) return String.format("%.1f µs", nanos / 1_000.0);
        if (nanos < 1_000_000_000L) return String.format("%.2f ms", nanos / 1_000_000.0);
        return String.format("%.2f s", nanos / 1_000_000_000.0);
    }

    // ---------- 表格 ----------

    private static String renderTable(ResultSet rs) {
        if (rs.columnCount() == 0) return Theme.muted("(empty)");

        int cols = rs.columnCount();
        String[] names = rs.getColumnNames();
        byte[] types = rs.getColumnTypes();
        List<Object[]> rows = rs.getRows();

        // 1. 把每个单元格格式化为字符串（不带颜色，用于宽度计算）
        String[][] cells = new String[rows.size()][cols];
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < cols; c++) {
                cells[r][c] = formatCellPlain(rows.get(r)[c], types[c]);
            }
        }

        // 2. 计算列宽
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = Math.min(MAX_DEFAULT_COL_WIDTH, names[c].length());
            for (int r = 0; r < rows.size(); r++) {
                widths[c] = Math.min(MAX_DEFAULT_COL_WIDTH, Math.max(widths[c], cells[r][c].length()));
            }
            widths[c] = Math.max(MIN_COL_WIDTH, widths[c]);
        }

        // 3. 受终端宽度约束，按比例收缩超宽列
        int termWidth = TerminalCaps.detectWidth();
        int totalNeeded = totalLineWidth(widths);
        if (totalNeeded > termWidth) {
            shrinkColumns(widths, totalNeeded - termWidth);
        }

        StringBuilder sb = new StringBuilder();
        // 顶边
        appendBorder(sb, widths, Theme.BoxChar.TL, Theme.BoxChar.T_DOWN, Theme.BoxChar.TR);
        // 表头
        sb.append(Theme.box(Theme.BoxChar.V_LINE));
        for (int c = 0; c < cols; c++) {
            String cell = truncate(names[c], widths[c]);
            sb.append(' ').append(Theme.header(padRight(cell, widths[c]))).append(' ');
            sb.append(Theme.box(Theme.BoxChar.V_LINE));
        }
        sb.append('\n');
        // 表头分隔
        appendBorder(sb, widths, Theme.BoxChar.T_RIGHT, Theme.BoxChar.CROSS, Theme.BoxChar.T_LEFT);
        // 数据行
        for (int r = 0; r < rows.size(); r++) {
            sb.append(Theme.box(Theme.BoxChar.V_LINE));
            for (int c = 0; c < cols; c++) {
                String plain = cells[r][c];
                String shown = truncate(plain, widths[c]);
                String colored = colorize(shown, rows.get(r)[c], types[c]);
                boolean rightAlign = ColumnType.isNumeric(types[c]) || types[c] == ColumnType.DATETIME;
                sb.append(' ');
                if (rightAlign) {
                    sb.append(padLeft(colored, widths[c], shown.length()));
                } else {
                    sb.append(padRight(colored, widths[c], shown.length()));
                }
                sb.append(' ').append(Theme.box(Theme.BoxChar.V_LINE));
            }
            sb.append('\n');
        }
        // 底边
        appendBorder(sb, widths, Theme.BoxChar.BL, Theme.BoxChar.T_UP, Theme.BoxChar.BR);
        // 去掉最后一个换行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /** 单元格的纯文本表示（不含颜色）。NULL 显示为 ASCII "NULL"，但渲染时会改成 ∅。 */
    private static String formatCellPlain(Object v, byte type) {
        if (v == null) return "NULL";
        if (type == ColumnType.DATETIME && v instanceof Long) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(new java.util.Date((Long) v));
        }
        if (type == ColumnType.FLOAT64 && v instanceof Double) {
            double d = (Double) v;
            // 去掉无意义的尾零
            String s = (d == Math.floor(d) && !Double.isInfinite(d)) ? String.format("%.1f", d) : String.valueOf(d);
            return s;
        }
        return String.valueOf(v);
    }

    private static String colorize(String shown, Object original, byte type) {
        if (original == null) return Theme.nullValue() + repeat(" ", Math.max(0, shown.length() - 1));
        switch (type) {
            case ColumnType.INT32:
            case ColumnType.INT64:
            case ColumnType.FLOAT64:
                return Theme.numeric(shown);
            case ColumnType.BOOL:
                return Theme.bool(shown);
            case ColumnType.DATETIME:
                return Theme.numeric(shown);
            case ColumnType.STRING:
            default:
                return Theme.stringy(shown);
        }
    }

    private static void appendBorder(StringBuilder sb, int[] widths,
                                     Theme.BoxChar left, Theme.BoxChar mid, Theme.BoxChar right) {
        sb.append(Theme.box(left));
        for (int i = 0; i < widths.length; i++) {
            sb.append(repeat(Theme.box(Theme.BoxChar.H_LINE), widths[i] + 2));
            sb.append(Theme.box(i == widths.length - 1 ? right : mid));
        }
        sb.append('\n');
    }

    private static int totalLineWidth(int[] widths) {
        int sum = 1; // 左竖线
        for (int w : widths) sum += w + 2 + 1;
        return sum;
    }

    private static void shrinkColumns(int[] widths, int overshoot) {
        // 从最宽的列开始收，每轮缩 1，直到塞下或所有列降到 MIN_COL_WIDTH
        while (overshoot > 0) {
            int maxIdx = 0;
            for (int i = 1; i < widths.length; i++) if (widths[i] > widths[maxIdx]) maxIdx = i;
            if (widths[maxIdx] <= MIN_COL_WIDTH) return;
            widths[maxIdx]--;
            overshoot--;
        }
    }

    private static String truncate(String s, int width) {
        if (s == null) s = "";
        if (s.length() <= width) return s;
        if (width <= 1) return s.substring(0, width);
        return s.substring(0, width - 1) + Theme.ellipsis();
    }

    private static String padRight(String s, int w) {
        return padRight(s, w, Ansi.visibleLength(s));
    }

    private static String padRight(String s, int w, int visibleLen) {
        if (visibleLen >= w) return s;
        return s + repeat(" ", w - visibleLen);
    }

    private static String padLeft(String s, int w, int visibleLen) {
        if (visibleLen >= w) return s;
        return repeat(" ", w - visibleLen) + s;
    }

    private static String repeat(String s, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
