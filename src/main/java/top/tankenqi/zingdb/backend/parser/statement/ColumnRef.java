package top.tankenqi.zingdb.backend.parser.statement;

/** 列引用（左值）。 */
public class ColumnRef implements Expr {
    public final String name;
    public ColumnRef(String name) { this.name = name; }
    @Override public String repr() { return name; }
}
