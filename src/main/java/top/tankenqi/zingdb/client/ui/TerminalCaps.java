package top.tankenqi.zingdb.client.ui;

import java.nio.charset.Charset;

/**
 * 终端能力探测：颜色 / Unicode / 是否是 TTY。
 *
 * 探测规则（保守优先）：
 *   - 颜色：System.console() != null 且 TERM 不为 "dumb"，且未设置 NO_COLOR 环境变量。
 *   - Unicode：默认字符集是 UTF-8 系列即认为支持。
 *   - 强制开关：环境变量 ZINGDB_COLOR / ZINGDB_UNICODE = "0|1" 覆盖默认。
 *
 * 用户也可以通过 --no-color CLI 参数强制关掉。
 */
public final class TerminalCaps {

    private TerminalCaps() {}

    public static boolean detectColor() {
        String forced = System.getenv("ZINGDB_COLOR");
        if ("0".equals(forced)) return false;
        if ("1".equals(forced)) return true;
        if (System.getenv("NO_COLOR") != null) return false;
        if (System.console() == null) return false;
        String term = System.getenv("TERM");
        if (term == null || "dumb".equalsIgnoreCase(term)) return false;
        return true;
    }

    public static boolean detectUnicode() {
        String forced = System.getenv("ZINGDB_UNICODE");
        if ("0".equals(forced)) return false;
        if ("1".equals(forced)) return true;
        String enc = Charset.defaultCharset().name();
        return enc != null && enc.toUpperCase().contains("UTF");
    }

    /** 终端宽度。检测不到时默认 100。 */
    public static int detectWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try { return Math.max(40, Integer.parseInt(cols.trim())); }
            catch (NumberFormatException ignored) {}
        }
        return 100;
    }
}
