package top.zhongnan.minidb.sql_compiler.statement;

public class Delete {
    public String tableName;
    public Where where;
    public Expr expr;     // 新 WHERE AST
}
