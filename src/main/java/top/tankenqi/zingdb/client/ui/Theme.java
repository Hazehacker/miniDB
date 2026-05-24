package top.tankenqi.zingdb.client.ui;

/**
 * 配色主题与 Unicode/ASCII 字符集中管理。
 *
 * 设计原则：所有「颜色 + 字符」决策集中在这里，UI 组件只用 Theme.something()，便于后续 :)
 *   - 一键切 dark/light 主题（暂时只实现 dark，留出 API）；
 *   - 在不支持 Unicode 的终端降级到 ASCII。
 */
public final class Theme {

    private static boolean unicode = true;

    public static void setUnicode(boolean v) { unicode = v; }
    public static boolean isUnicode() { return unicode; }

    private Theme() {}

    // ---------- 语义化 helper ----------
    public static String brand(String s)    { return Ansi.brightCyan(s); }
    public static String muted(String s)    { return Ansi.gray(s); }
    public static String success(String s)  { return Ansi.brightGreen(s); }
    public static String warn(String s)     { return Ansi.brightYellow(s); }
    public static String danger(String s)   { return Ansi.brightRed(s); }
    public static String header(String s)   { return Ansi.bold(Ansi.cyan(s)); }
    public static String value(String s)    { return s; }
    public static String numeric(String s)  { return Ansi.fg256(180, s); }   // 暖黄
    public static String stringy(String s)  { return Ansi.fg256(151, s); }   // 淡绿
    public static String bool(String s)     { return Ansi.fg256(141, s); }   // 紫
    public static String nullValue()        { return Ansi.gray("∅"); }
    public static String errorTag(String s) { return Ansi.bold(Ansi.brightRed(s)); }

    // ---------- 表格字符 ----------
    public static String box(BoxChar ch) {
        if (unicode) return ch.uni;
        return ch.ascii;
    }

    public enum BoxChar {
        H_LINE("─", "-"),
        V_LINE("│", "|"),
        TL("┌", "+"), TR("┐", "+"), BL("└", "+"), BR("┘", "+"),
        T_DOWN("┬", "+"), T_UP("┴", "+"), T_RIGHT("├", "+"), T_LEFT("┤", "+"),
        CROSS("┼", "+"),
        CARD_TL("╭", "+"), CARD_TR("╮", "+"), CARD_BL("╰", "+"), CARD_BR("╯", "+");

        final String uni, ascii;
        BoxChar(String u, String a) { this.uni = u; this.ascii = a; }
    }

    public static String ellipsis() { return unicode ? "…" : "..."; }
    public static String bullet()   { return unicode ? "·" : "*"; }
    public static String arrow()    { return unicode ? "›" : ">"; }
}
