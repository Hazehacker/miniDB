package top.tankenqi.zingdb.client.ui;

/**
 * 反斜杠开头的元命令（参考 psql）。
 *
 *   \h           help
 *   \q           quit
 *   \dt          show tables             -> 转换为 SQL: show
 *   \d <table>   describe table          -> 转换为 SQL: desc <table>
 *   \timing      切换是否显示耗时
 *   \json        切换 JSON / 表格输出（占位，目前只切标志）
 *   \!           重连服务器
 *
 * MetaCommand 不直接执行，由 Shell 决定 —— 这里只做解析与帮助。
 */
public final class MetaCommand {

    public enum Kind { HELP, QUIT, LIST_TABLES, DESCRIBE, STATS, TOGGLE_TIMING, TOGGLE_JSON, RECONNECT, UNKNOWN }

    public final Kind kind;
    public final String argument;

    public MetaCommand(Kind kind, String argument) {
        this.kind = kind;
        this.argument = argument;
    }

    /** 输入若不是反斜杠开头返回 null。 */
    public static MetaCommand parse(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.charAt(0) != '\\') return null;
        String body = s.substring(1).trim();
        if (body.equalsIgnoreCase("h") || body.equalsIgnoreCase("help") || body.equals("?"))
            return new MetaCommand(Kind.HELP, null);
        if (body.equalsIgnoreCase("q") || body.equalsIgnoreCase("quit") || body.equalsIgnoreCase("exit"))
            return new MetaCommand(Kind.QUIT, null);
        if (body.equalsIgnoreCase("dt"))
            return new MetaCommand(Kind.LIST_TABLES, null);
        if (body.equalsIgnoreCase("stats"))
            return new MetaCommand(Kind.STATS, null);
        if (body.toLowerCase().startsWith("d ") || body.toLowerCase().startsWith("d\t")) {
            String arg = body.substring(2).trim();
            return new MetaCommand(Kind.DESCRIBE, arg.isEmpty() ? null : arg);
        }
        if (body.equalsIgnoreCase("d"))
            return new MetaCommand(Kind.DESCRIBE, null);
        if (body.equalsIgnoreCase("timing"))
            return new MetaCommand(Kind.TOGGLE_TIMING, null);
        if (body.equalsIgnoreCase("json"))
            return new MetaCommand(Kind.TOGGLE_JSON, null);
        if (body.equals("!") || body.equalsIgnoreCase("reconnect"))
            return new MetaCommand(Kind.RECONNECT, null);
        return new MetaCommand(Kind.UNKNOWN, body);
    }
}
