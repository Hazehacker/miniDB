package top.tankenqi.zingdb.client;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import top.tankenqi.zingdb.client.ui.Ansi;
import top.tankenqi.zingdb.client.ui.TerminalCaps;
import top.tankenqi.zingdb.client.ui.Theme;
import top.tankenqi.zingdb.transport.Encoder;
import top.tankenqi.zingdb.transport.Package;
import top.tankenqi.zingdb.transport.Packager;
import top.tankenqi.zingdb.transport.Transporter;

/**
 * 客户端入口。
 *
 * 用法：
 *   zingdb-client [--host HOST] [--port PORT] [--no-color] [-e SQL] [-f FILE]
 *
 * -e / -f 模式下，不进入交互 REPL：执行完即退出，stdout 输出渲染结果。
 */
public class Launcher {

    public static void main(String[] args) {
        Options opts = new Options();
        opts.addOption("h", "host", true, "server host, default 127.0.0.1");
        opts.addOption("p", "port", true, "server port, default 9999");
        opts.addOption(null, "no-color", false, "disable ANSI colors");
        opts.addOption("e", "execute", true, "execute a single SQL statement then exit");
        opts.addOption("f", "file", true, "execute SQL statements from a file then exit");
        opts.addOption(null, "help", false, "show this help");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(opts, args);
        } catch (ParseException e) {
            System.err.println("invalid arguments: " + e.getMessage());
            new HelpFormatter().printHelp("zingdb-client", opts);
            System.exit(2);
            return;
        }
        if (cmd.hasOption("help")) {
            new HelpFormatter().printHelp("zingdb-client", opts);
            return;
        }

        String host = cmd.getOptionValue("host", "127.0.0.1");
        int port;
        try { port = Integer.parseInt(cmd.getOptionValue("port", "9999")); }
        catch (NumberFormatException e) { System.err.println("invalid port"); System.exit(2); return; }

        // 颜色 / Unicode 能力决策
        boolean wantColor = !cmd.hasOption("no-color") && TerminalCaps.detectColor();
        Ansi.setEnabled(wantColor);
        Theme.setUnicode(TerminalCaps.detectUnicode());

        // 建立连接
        Client client;
        try {
            Socket sock = new Socket(host, port);
            client = new Client(new Packager(new Transporter(sock), new Encoder()));
        } catch (IOException e) {
            System.err.println(Theme.errorTag("connect failed") + " " + e.getMessage());
            System.exit(1);
            return;
        }

        // 非交互模式：-e / -f
        if (cmd.hasOption("execute")) {
            runOneShot(client, cmd.getOptionValue("execute"));
            return;
        }
        if (cmd.hasOption("file")) {
            runFile(client, cmd.getOptionValue("file"));
            return;
        }

        // 交互模式
        new Shell(client, host, port).run();
    }

    private static void runOneShot(Client client, String sql) {
        try {
            Package resp = client.execute(sql);
            System.out.println(top.tankenqi.zingdb.client.ui.TableRenderer.render(resp));
            if (resp.isError()) System.exit(1);
        } catch (Exception e) {
            System.err.println("transport error: " + e.getMessage());
            System.exit(1);
        } finally {
            client.close();
        }
    }

    private static void runFile(Client client, String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            StringBuilder buf = new StringBuilder();
            String line;
            int errs = 0;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) continue;
                buf.append(' ').append(line);
                if (t.endsWith(";")) {
                    String sql = buf.toString().trim();
                    if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
                    buf.setLength(0);
                    Package resp = client.execute(sql);
                    System.out.println(top.tankenqi.zingdb.client.ui.TableRenderer.render(resp));
                    if (resp.isError()) errs++;
                }
            }
            // 最后一条可能没有 ;
            if (buf.toString().trim().length() > 0) {
                Package resp = client.execute(buf.toString().trim());
                System.out.println(top.tankenqi.zingdb.client.ui.TableRenderer.render(resp));
                if (resp.isError()) errs++;
            }
            if (errs > 0) System.exit(1);
        } catch (IOException e) {
            System.err.println("file read error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("transport error: " + e.getMessage());
            System.exit(1);
        } finally {
            client.close();
        }
    }
}
