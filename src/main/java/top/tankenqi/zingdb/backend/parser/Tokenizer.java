package top.tankenqi.zingdb.backend.parser;

import top.tankenqi.zingdb.common.Error;

/**
 * 词法分析器（v2）。
 *
 * 相对旧版本（仅识别裸字 / 单字符符号 / 引号字符串）的增强：
 *   1. 数字字面量：识别整型与浮点型（如 -12 / 3.14 / 1e9）；负号在前是否构成数字由调用方上下文判断，
 *      这里只在数字首位连带识别 '-'，且仅在合法上下文出现（紧跟在比较符号/逗号/括号/start 之后）。
 *      为简化实现，本词法器把 '-' 作为独立符号；负号由 Parser 在解析字面量时识别（更可控）。
 *   2. 多字符符号：!=  <>  >=  <=
 *   3. 单行注释 `-- ... \n` 与块注释 `/* ... *\/`（块注释支持任意嵌套层数前的内容）
 *   4. 关键字保留原样返回，由 Parser 通过 toLowerCase 后比对（关键字大小写不敏感，标识符大小写敏感）
 */
public class Tokenizer {

    private final byte[] stat;
    private int pos;
    private String currentToken;
    private boolean flushToken;
    private Exception err;

    public Tokenizer(byte[] stat) {
        this.stat = stat;
        this.pos = 0;
        this.currentToken = "";
        this.flushToken = true;
    }

    public String peek() throws Exception {
        if (err != null) throw err;
        if (flushToken) {
            try {
                currentToken = next();
            } catch (Exception e) {
                err = e;
                throw e;
            }
            flushToken = false;
        }
        return currentToken;
    }

    public void pop() { flushToken = true; }

    public byte[] errStat() {
        byte[] res = new byte[stat.length + 3];
        System.arraycopy(stat, 0, res, 0, pos);
        System.arraycopy("<< ".getBytes(), 0, res, pos, 3);
        System.arraycopy(stat, pos, res, pos + 3, stat.length - pos);
        return res;
    }

    private void popByte() {
        pos++;
        if (pos > stat.length) pos = stat.length;
    }

    private Byte peekByte() {
        if (pos == stat.length) return null;
        return stat[pos];
    }

    private Byte peekByteAt(int offset) {
        int p = pos + offset;
        if (p >= stat.length || p < 0) return null;
        return stat[p];
    }

    private String next() throws Exception {
        if (err != null) throw err;
        return nextMetaState();
    }

    private String nextMetaState() throws Exception {
        while (true) {
            skipBlanks();
            // 处理注释
            Byte b = peekByte();
            if (b == null) return "";
            if (b == '-' && peekByteAt(1) != null && peekByteAt(1) == '-') {
                skipLineComment();
                continue;
            }
            if (b == '/' && peekByteAt(1) != null && peekByteAt(1) == '*') {
                skipBlockComment();
                continue;
            }
            break;
        }

        Byte b0 = peekByte();
        if (b0 == null) return "";
        byte b = b0;

        // 多字符符号
        if (b == '!' && peekByteAt(1) != null && peekByteAt(1) == '=') {
            popByte(); popByte(); return "!=";
        }
        if (b == '<' && peekByteAt(1) != null && peekByteAt(1) == '=') {
            popByte(); popByte(); return "<=";
        }
        if (b == '>' && peekByteAt(1) != null && peekByteAt(1) == '=') {
            popByte(); popByte(); return ">=";
        }
        if (b == '<' && peekByteAt(1) != null && peekByteAt(1) == '>') {
            popByte(); popByte(); return "<>";
        }

        if (isSymbol(b)) {
            popByte();
            return new String(new byte[]{b});
        }
        if (b == '"' || b == '\'') {
            return nextQuoteState();
        }
        if (isDigit(b) || b == '.') {
            return nextNumberState();
        }
        if (isAlphaBeta(b) || b == '_') {
            return nextTokenState();
        }

        err = Error.InvalidCommandException;
        throw err;
    }

    private void skipBlanks() {
        while (true) {
            Byte b = peekByte();
            if (b == null || !isBlank(b)) return;
            popByte();
        }
    }

    private void skipLineComment() {
        // 已确认开头是 "--"
        popByte(); popByte();
        while (true) {
            Byte b = peekByte();
            if (b == null) return;
            popByte();
            if (b == '\n') return;
        }
    }

    private void skipBlockComment() throws Exception {
        // 已确认开头是 "/*"
        popByte(); popByte();
        while (true) {
            Byte b = peekByte();
            if (b == null) {
                err = Error.InvalidCommandException;
                throw err;
            }
            if (b == '*' && peekByteAt(1) != null && peekByteAt(1) == '/') {
                popByte(); popByte();
                return;
            }
            popByte();
        }
    }

    private String nextTokenState() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            Byte b = peekByte();
            if (b == null || !(isAlphaBeta(b) || isDigit(b) || b == '_')) {
                return sb.toString();
            }
            sb.append((char) b.byteValue());
            popByte();
        }
    }

    /**
     * 识别整数 / 浮点字面量。
     *
     * 规则：[digits][.][digits][e/E[+-]digits]
     * 至少包含一个数字。负号在 Parser 一元运算符层处理，本方法不识别 '-'。
     */
    private String nextNumberState() {
        StringBuilder sb = new StringBuilder();
        // 整数部分
        while (true) {
            Byte b = peekByte();
            if (b == null || !isDigit(b)) break;
            sb.append((char) b.byteValue()); popByte();
        }
        // 小数部分
        Byte b = peekByte();
        if (b != null && b == '.') {
            sb.append('.'); popByte();
            while (true) {
                Byte bb = peekByte();
                if (bb == null || !isDigit(bb)) break;
                sb.append((char) bb.byteValue()); popByte();
            }
        }
        // 指数部分
        b = peekByte();
        if (b != null && (b == 'e' || b == 'E')) {
            sb.append((char) b.byteValue()); popByte();
            Byte sign = peekByte();
            if (sign != null && (sign == '+' || sign == '-')) {
                sb.append((char) sign.byteValue()); popByte();
            }
            while (true) {
                Byte bb = peekByte();
                if (bb == null || !isDigit(bb)) break;
                sb.append((char) bb.byteValue()); popByte();
            }
        }
        return sb.toString();
    }

    private String nextQuoteState() throws Exception {
        byte quote = peekByte();
        popByte();
        StringBuilder sb = new StringBuilder();
        while (true) {
            Byte b = peekByte();
            if (b == null) {
                err = Error.InvalidCommandException;
                throw err;
            }
            if (b == quote) {
                popByte();
                break;
            }
            sb.append((char) b.byteValue());
            popByte();
        }
        return sb.toString();
    }

    static boolean isDigit(byte b) {
        return (b >= '0' && b <= '9');
    }

    static boolean isAlphaBeta(byte b) {
        return ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z'));
    }

    static boolean isSymbol(byte b) {
        return (b == '>' || b == '<' || b == '=' || b == '*' ||
                b == ',' || b == '(' || b == ')' || b == ';' ||
                b == '+' || b == '-' || b == '/' || b == '%');
    }

    static boolean isBlank(byte b) {
        return (b == '\n' || b == ' ' || b == '\t' || b == '\r');
    }
}
