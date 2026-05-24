package top.tankenqi.zingdb.backend.parser.statement;

import java.util.List;

/** col IN (v1, v2, ...) */
public class InExpr implements Expr {
    public final ColumnRef column;
    public final List<Literal> values;
    public final boolean negated;     // NOT IN

    public InExpr(ColumnRef column, List<Literal> values, boolean negated) {
        this.column = column;
        this.values = values;
        this.negated = negated;
    }

    @Override
    public String repr() {
        StringBuilder sb = new StringBuilder(column.repr());
        sb.append(negated ? " NOT IN (" : " IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i).repr());
        }
        return sb.append(')').toString();
    }
}
