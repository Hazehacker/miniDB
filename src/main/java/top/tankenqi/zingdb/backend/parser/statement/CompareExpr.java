package top.tankenqi.zingdb.backend.parser.statement;

/**
 * 比较表达式：col op literal。
 *
 * op ∈ { =  !=  <  <=  >  >=  IS_NULL  IS_NOT_NULL }
 * 当 op 为 IS_NULL / IS_NOT_NULL 时 right 为 null。
 */
public class CompareExpr implements Expr {
    public static final String EQ = "=";
    public static final String NEQ = "!=";
    public static final String LT = "<";
    public static final String LE = "<=";
    public static final String GT = ">";
    public static final String GE = ">=";
    public static final String IS_NULL = "is null";
    public static final String IS_NOT_NULL = "is not null";

    public final String op;
    public final ColumnRef left;
    public final Literal right;       // 可空

    public CompareExpr(String op, ColumnRef left, Literal right) {
        this.op = op;
        this.left = left;
        this.right = right;
    }

    @Override
    public String repr() {
        if (right == null) return left.repr() + " " + op;
        return left.repr() + " " + op + " " + right.repr();
    }
}
