package top.tankenqi.zingdb.backend.parser.statement;

/** col BETWEEN a AND b （闭区间）. */
public class BetweenExpr implements Expr {
    public final ColumnRef column;
    public final Literal lo;
    public final Literal hi;
    public final boolean negated;     // NOT BETWEEN

    public BetweenExpr(ColumnRef column, Literal lo, Literal hi, boolean negated) {
        this.column = column;
        this.lo = lo;
        this.hi = hi;
        this.negated = negated;
    }

    @Override
    public String repr() {
        return column.repr() + (negated ? " NOT BETWEEN " : " BETWEEN ")
            + lo.repr() + " AND " + hi.repr();
    }
}
