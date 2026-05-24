package top.tankenqi.zingdb.backend.parser.statement;

public class Delete {
    public String tableName;
    public Where where;
    public Expr expr;     // 新 WHERE AST
}
