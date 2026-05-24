package top.tankenqi.zingdb.client.ui;

/**
 * ANSI 转义工具。
 *
 * 设计：
 *   - 单一开关 {@link #setEnabled(boolean)}：关掉就所有 wrap 方法返回原字符串，避免到处 if。
 *   - 只用 8-color + bright + 256-color 子集，覆盖绝大多数终端；不依赖 24-bit truecolor。
 *   - 不依赖 Jansi —— 在 macOS/iTerm2/Terminal.app 与 Linux 终端原生即可工作。
 */
public final class Ansi {
    /** ESC[ 的开头。源代码里写成 Unicode 转义以保持可读性。 */
    public static final String CSI = "[";
    public static final String RESET = CSI + "0m";

    private static volatile boolean enabled = true;

    public static void setEnabled(boolean v) { enabled = v; }
    public static boolean isEnabled() { return enabled; }

    private Ansi() {}

    // ---------- 颜色 ----------
    public static String fg256(int code, String s) {
        if (!enabled) return s;
        return CSI + "38;5;" + code + "m" + s + RESET;
    }
    public static String bg256(int code, String s) {
        if (!enabled) return s;
        return CSI + "48;5;" + code + "m" + s + RESET;
    }
    public static String dim(String s)    { return wrap(s, "2"); }
    public static String bold(String s)   { return wrap(s, "1"); }
    public static String italic(String s) { return wrap(s, "3"); }
    public static String reverse(String s){ return wrap(s, "7"); }

    public static String red(String s)    { return wrap(s, "31"); }
    public static String green(String s)  { return wrap(s, "32"); }
    public static String yellow(String s) { return wrap(s, "33"); }
    public static String blue(String s)   { return wrap(s, "34"); }
    public static String magenta(String s){ return wrap(s, "35"); }
    public static String cyan(String s)   { return wrap(s, "36"); }
    public static String white(String s)  { return wrap(s, "37"); }
    public static String gray(String s)   { return fg256(245, s); }

    public static String brightCyan(String s)  { return wrap(s, "96"); }
    public static String brightGreen(String s) { return wrap(s, "92"); }
    public static String brightYellow(String s){ return wrap(s, "93"); }
    public static String brightRed(String s)   { return wrap(s, "91"); }

    private static String wrap(String s, String code) {
        if (!enabled) return s;
        return CSI + code + "m" + s + RESET;
    }

    /** 计算字符串的可见宽度（去掉 ESC 转义序列，按字符数粗略估算；不处理 CJK 宽字符）。 */
    public static int visibleLength(String s) {
        if (s == null) return 0;
        int n = 0;
        boolean inEsc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inEsc) {
                if (c == 'm') inEsc = false;
                continue;
            }
            if (c == 0x1b) { inEsc = true; continue; }
            n++;
        }
        return n;
    }
}
