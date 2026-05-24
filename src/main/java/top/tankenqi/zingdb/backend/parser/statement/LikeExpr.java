package top.tankenqi.zingdb.backend.parser.statement;

/** col LIKE 'pattern'  —— 仅支持 % (任意字符串) 与 _ (任意单字符) 通配。 */
public class LikeExpr implements Expr {
    public final ColumnRef column;
    public final Literal pattern;
    public final boolean negated;     // NOT LIKE

    public LikeExpr(ColumnRef column, Literal pattern, boolean negated) {
        this.column = column;
        this.pattern = pattern;
        this.negated = negated;
    }

    @Override
    public String repr() {
        return column.repr() + (negated ? " NOT LIKE " : " LIKE ") + pattern.repr();
    }
}
