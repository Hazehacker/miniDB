package top.tankenqi.zingdb.client.ui;

/**
 * 帮助文本输出（响应 \h）。
 */
public final class HelpPrinter {

    private HelpPrinter() {}

    public static String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(Theme.header("Meta commands")).append('\n');
        line(sb, "\\h, \\help, \\?", "show this help");
        line(sb, "\\q, \\quit, \\exit", "leave the client");
        line(sb, "\\dt", "list all tables (alias of `show`)");
        line(sb, "\\d <table>", "describe a table (alias of `desc <table>`)");
        line(sb, "\\stats", "show server stats (alias of `show stats`)");
        line(sb, "\\timing", "toggle showing query execution time");
        line(sb, "\\json", "toggle JSON output mode");
        line(sb, "\\!", "reconnect to the server");
        sb.append('\n');
        sb.append(Theme.header("SQL")).append('\n');
        line(sb, "create table", "create table t a int32, b string, (index a)");
        line(sb, "drop table",   "drop table t");
        line(sb, "insert into",  "insert into t values (1, 'alice')");
        line(sb, "select",       "select [* | col,...] from t [where expr] [order by ...] [limit n offset m]");
        line(sb, "update",       "update t set col = v [where expr]");
        line(sb, "delete",       "delete from t [where expr]");
        line(sb, "tx",           "begin [isolation level (read committed | repeatable read)]");
        line(sb, "",             "commit | abort");
        sb.append('\n');
        sb.append(Theme.header("Expressions in WHERE")).append('\n');
        line(sb, "comparison",   "=  !=  <>  <  <=  >  >=");
        line(sb, "logical",      "AND, OR, NOT, ( ... )");
        line(sb, "ranges/sets",  "IN (...), NOT IN, BETWEEN a AND b, LIKE 'a%' (% _ )");
        line(sb, "null",         "IS NULL, IS NOT NULL");
        sb.append('\n');
        sb.append(Theme.muted("type SQL terminated by `;` (or just press enter) to execute."));
        return sb.toString();
    }

    private static void line(StringBuilder sb, String head, String detail) {
        sb.append("  ");
        sb.append(Theme.numeric(pad(head, 22)));
        sb.append("  ").append(Theme.muted(detail)).append('\n');
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}
