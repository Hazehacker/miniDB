package top.tankenqi.zingdb.client;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReader.Option;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import top.tankenqi.zingdb.client.ui.Ansi;
import top.tankenqi.zingdb.client.ui.Banner;
import top.tankenqi.zingdb.client.ui.HelpPrinter;
import top.tankenqi.zingdb.client.ui.MetaCommand;
import top.tankenqi.zingdb.client.ui.Prompter;
import top.tankenqi.zingdb.client.ui.TableRenderer;
import top.tankenqi.zingdb.client.ui.Theme;
import top.tankenqi.zingdb.transport.Package;

/**
 * JLine 3 驱动的交互式 SQL Shell。
 *
 * 关键行为：
 *   1. 多行输入：以 ";" 结尾或元命令一行提交。
 *   2. 历史持久化在 ~/.zingdb_history（JLine 自带）。
 *   3. Tab 补全：SQL 关键字 + 已知元命令；不查表名（避免每次都打到服务端）。
 *   4. 提示符颜色根据事务状态变化：默认青色 zingdb> / 事务黄色 zingdb* / 错误红色 zingdb!
 *   5. 元命令 \h \q \dt \d \timing \json 在客户端就地处理（部分映射为 SQL 透传）。
 */
public class Shell {

    /** 用于 Tab 补全的关键字集合。 */
    private static final List<String> KEYWORDS = Arrays.asList(
        "select", "from", "where", "and", "or", "not", "in", "between", "like",
        "is", "null", "order", "by", "asc", "desc", "limit", "offset",
        "insert", "into", "values", "update", "set", "delete",
        "create", "table", "drop", "index",
        "show", "tables", "describe", "count", "stats",
        "begin", "commit", "abort", "isolation", "level", "read", "committed", "repeatable",
        "int32", "int64", "string", "float64", "bool", "datetime", "true", "false"
    );

    private final Client client;
    private final String host;
    private final int port;
    private final Prompter prompter = new Prompter();
    private boolean showTiming = true;

    public Shell(Client client, String host, int port) {
        this.client = client;
        this.host = host;
        this.port = port;
    }

    public void run() {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder()
                .system(true)
                .build();
            runWith(terminal);
        } catch (Exception e) {
            System.err.println("failed to init terminal: " + e.getMessage());
        } finally {
            if (terminal != null) try { terminal.close(); } catch (Exception ignored) {}
            client.close();
        }
    }

    private void runWith(Terminal terminal) {
        PrintWriter out = terminal.writer();
        out.println(Banner.render(host, port));
        out.println();
        out.flush();

        Set<String> kwSet = new HashSet<>(KEYWORDS);

        // JLine 的 DefaultParser 默认把 '\' 当成 shell 风格的转义符（\h -> h），
        // 这会把我们的元命令前缀吃掉。显式清空 escapeChars 才能让 '\' 原样传过来。
        DefaultParser jlineParser = new DefaultParser();
        jlineParser.setEscapeChars(null);

        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .history(new DefaultHistory())
            .parser(jlineParser)
            .completer((rdr, line, candidates) -> {
                String word = line.word().toLowerCase();
                if (word.startsWith("\\")) {
                    for (String mc : Arrays.asList("\\h", "\\help", "\\q", "\\quit", "\\exit",
                                                   "\\dt", "\\d ", "\\stats", "\\timing", "\\json", "\\!")) {
                        if (mc.startsWith(word)) candidates.add(new Candidate(mc));
                    }
                    return;
                }
                for (String kw : kwSet) {
                    if (kw.startsWith(word)) candidates.add(new Candidate(kw));
                }
            })
            .variable(LineReader.HISTORY_FILE, System.getProperty("user.home") + "/.zingdb_history")
            .option(Option.HISTORY_BEEP, false)
            .option(Option.AUTO_FRESH_LINE, true)
            .build();

        StringBuilder buf = new StringBuilder();
        while (true) {
            String prompt = buf.length() == 0 ? prompter.mainPrompt() : prompter.continuePrompt();
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException ui) {     // Ctrl-C
                buf.setLength(0);
                continue;
            } catch (EndOfFileException eof) {        // Ctrl-D
                out.println(Theme.muted("bye."));
                return;
            }
            if (line == null) return;
            String trimmed = line.trim();

            // 空行：清空缓冲、忽略
            if (trimmed.isEmpty() && buf.length() == 0) continue;

            // 元命令（仅在缓冲为空时识别，避免与 SQL 字面冲突）
            //   '\' 前缀始终走元命令；额外把光秃秃的 help/quit/exit 也当元命令处理 —— 用户友好。
            if (buf.length() == 0 && isMetaCommand(trimmed)) {
                MetaCommand mc = parseMetaCommand(trimmed);
                if (handleMeta(mc, out)) continue; else return;
            }

            // 普通输入：追加缓冲，遇到 ; 或单行 SQL 提交
            if (buf.length() > 0) buf.append(' ');
            buf.append(line);

            String pending = buf.toString().trim();
            boolean endsWithSemi = pending.endsWith(";");
            if (endsWithSemi) {
                pending = pending.substring(0, pending.length() - 1).trim();
                buf.setLength(0);
                executeSql(pending, out);
                continue;
            }

            // 没有 ; 时也允许通过空行执行（更友好），但要看是否包含已知开头关键字
            if (!endsWithSemi && looksLikeCompleteStatement(buf.toString())) {
                String sql = buf.toString().trim();
                buf.setLength(0);
                executeSql(sql, out);
            }
        }
    }

    /** 简单启发式：单行 + 不以续行连接词结尾，视为已完整。 */
    private boolean looksLikeCompleteStatement(String s) {
        String t = s.trim();
        if (t.contains("\n")) return false;
        String low = t.toLowerCase();
        // 续行连接词作为最后一个 token 时，认为还没写完，让用户继续输入
        for (String kw : new String[]{" and", " or", " ,", "(", ","}) {
            if (low.endsWith(kw)) return false;
        }
        return true;
    }

    private boolean handleMeta(MetaCommand mc, PrintWriter out) {
        switch (mc.kind) {
            case HELP:
                out.println(HelpPrinter.render());
                return true;
            case QUIT:
                out.println(Theme.muted("bye."));
                return false;
            case LIST_TABLES:
                executeSql("show", out);
                return true;
            case STATS:
                executeSql("show stats", out);
                return true;
            case DESCRIBE:
                if (mc.argument == null) {
                    out.println(Theme.warn("usage: \\d <table>"));
                } else {
                    executeSql("desc " + mc.argument, out);
                }
                return true;
            case TOGGLE_TIMING:
                showTiming = !showTiming;
                out.println(Theme.muted("timing: " + (showTiming ? "on" : "off")));
                return true;
            case TOGGLE_JSON:
                out.println(Theme.muted("(json mode not implemented yet)"));
                return true;
            case RECONNECT:
                out.println(Theme.muted("(reconnect not implemented yet; restart the client)"));
                return true;
            case UNKNOWN:
            default:
                out.println(Theme.warn("unknown meta command: \\") + mc.argument
                    + "  " + Theme.muted("(try \\h)"));
                return true;
        }
    }

    private void executeSql(String sql, PrintWriter out) {
        if (sql.isEmpty()) return;
        try {
            Package resp = client.execute(sql);
            if (!showTiming) resp.setElapsedNanos(0);
            String rendered = TableRenderer.render(resp);
            out.println(rendered);

            // 更新事务状态
            boolean inTx = inferInTx(sql, resp, prompter.state() == Prompter.State.IN_TX);
            prompter.onResult(inTx, resp.isError());
        } catch (Exception e) {
            out.println(Theme.errorTag("✗ TRANSPORT") + " " + Ansi.red(e.getMessage() == null ? e.toString() : e.getMessage()));
            prompter.onResult(prompter.state() == Prompter.State.IN_TX, true);
        }
        out.flush();
    }

    /**
     * 根据 SQL 与服务端响应推断当前事务状态。
     *
     * 服务端的 OK message 在 begin 时是 "begin"，commit 是 "commit"，abort 是 "abort"。
     * 客户端基于此切换提示符（无需服务端额外暴露）。
     */
    private boolean inferInTx(String sql, Package resp, boolean prevInTx) {
        if (resp.isError()) return prevInTx;
        if (resp.isOk()) {
            String m = resp.getMessage();
            if (m == null) return prevInTx;
            if (m.equals("begin")) return true;
            if (m.equals("commit") || m.equals("abort")) return false;
        }
        return prevInTx;
    }

    /**
     * 判断一行输入是不是元命令。两种形式都接受：
     *   1. '\' 前缀（标准）—— "\h", "\dt", "\d users"
     *   2. 光秃秃的常见词 —— "help", "quit", "exit"
     * 这样新手不会因为忘记反斜杠就吃 PR-0001 错。
     */
    private static boolean isMetaCommand(String line) {
        if (line == null || line.isEmpty()) return false;
        if (line.charAt(0) == '\\') return true;
        String first = line.split("\\s+", 2)[0].toLowerCase();
        return first.equals("help") || first.equals("quit") || first.equals("exit");
    }

    /**
     * 解析元命令。比 MetaCommand.parse 更宽容：
     *   - "help" / "quit" / "exit" 视同 "\h" / "\q"
     *   - "\h help" 这种多余参数被忽略而不是当成未知命令
     */
    private static MetaCommand parseMetaCommand(String line) {
        String normalized;
        if (line.charAt(0) == '\\') {
            // 取第一个 token，丢掉后续参数（\d <table> 是唯一带参数的，下面单独处理）
            String body = line.substring(1).trim();
            String head = body.split("\\s+", 2)[0];
            String rest = body.length() > head.length() ? body.substring(head.length()).trim() : "";
            // \d 需要保留参数
            if (head.equalsIgnoreCase("d") && !rest.isEmpty()) {
                return MetaCommand.parse("\\d " + rest);
            }
            normalized = "\\" + head;
        } else {
            String head = line.split("\\s+", 2)[0].toLowerCase();
            if (head.equals("help")) normalized = "\\h";
            else if (head.equals("quit") || head.equals("exit")) normalized = "\\q";
            else normalized = "\\" + head;
        }
        return MetaCommand.parse(normalized);
    }
}
