package top.tankenqi.zingdb.client.ui;

/**
 * 客户端启动 banner。卡片状的小框，里面写连接信息和帮助提示。
 *
 *   ╭─────────────────────────────────────────────╮
 *   │  ZingDB  v0.2.0                             │
 *   │  connected to 127.0.0.1:9999  ·  ready      │
 *   │  \h help   \dt tables   \q quit             │
 *   ╰─────────────────────────────────────────────╯
 */
public final class Banner {

    public static final String VERSION = "0.2.0";

    private Banner() {}

    public static String render(String host, int port) {
        String l1 = "  " + Theme.brand("ZingDB") + "  " + Theme.muted("v" + VERSION);
        String l2 = "  " + Theme.muted("connected to ") + host + ":" + port
            + Theme.muted("  " + Theme.bullet() + "  ready");
        String l3 = "  " + Theme.muted("\\h help   \\dt tables   \\d <table> schema   \\q quit");

        int w = Math.max(Ansi.visibleLength(l1),
                Math.max(Ansi.visibleLength(l2), Ansi.visibleLength(l3))) + 4;

        StringBuilder sb = new StringBuilder();
        sb.append(Theme.box(Theme.BoxChar.CARD_TL));
        sb.append(repeat(Theme.box(Theme.BoxChar.H_LINE), w));
        sb.append(Theme.box(Theme.BoxChar.CARD_TR)).append('\n');
        appendCardLine(sb, l1, w);
        appendCardLine(sb, l2, w);
        appendCardLine(sb, l3, w);
        sb.append(Theme.box(Theme.BoxChar.CARD_BL));
        sb.append(repeat(Theme.box(Theme.BoxChar.H_LINE), w));
        sb.append(Theme.box(Theme.BoxChar.CARD_BR));
        return sb.toString();
    }

    private static void appendCardLine(StringBuilder sb, String content, int w) {
        sb.append(Theme.box(Theme.BoxChar.V_LINE));
        sb.append(content);
        int pad = w - Ansi.visibleLength(content);
        for (int i = 0; i < pad; i++) sb.append(' ');
        sb.append(Theme.box(Theme.BoxChar.V_LINE)).append('\n');
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
