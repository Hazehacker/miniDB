package top.tankenqi.zingdb.backend.parser.statement;

/**
 * 字面量（右值）。
 *
 * raw 是 Tokenizer 输出的原始 token：
 *   - 字符串字面量已去掉引号
 *   - 数值字面量保留 0-9/./e/E/+/-
 *   - 布尔字面量为 "true" / "false"
 *   - NULL 字面量 isNull=true，此时 raw 为 null
 * 类型转换推迟到 ExprEvaluator/Field 阶段按对端字段类型完成。
 */
public class Literal implements Expr {
    public final String raw;
    public final boolean isNull;

    public Literal(String raw) {
        this.raw = raw;
        this.isNull = false;
    }

    private Literal() {
        this.raw = null;
        this.isNull = true;
    }

    public static Literal nullLiteral() { return new Literal(); }

    @Override
    public String repr() {
        if (isNull) return "NULL";
        return "'" + raw + "'";
    }
}
