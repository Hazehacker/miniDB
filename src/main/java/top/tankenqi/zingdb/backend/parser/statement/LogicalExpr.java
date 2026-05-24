package top.tankenqi.zingdb.backend.parser.statement;

/** AND / OR / NOT. */
public class LogicalExpr implements Expr {
    public static final String AND = "and";
    public static final String OR = "or";
    public static final String NOT = "not";

    public final String op;
    public final Expr left;
    public final Expr right;          // NOT 时为 null

    public LogicalExpr(String op, Expr left, Expr right) {
        this.op = op;
        this.left = left;
        this.right = right;
    }

    @Override
    public String repr() {
        if (NOT.equals(op)) return "(NOT " + left.repr() + ")";
        return "(" + left.repr() + " " + op.toUpperCase() + " " + right.repr() + ")";
    }
}
