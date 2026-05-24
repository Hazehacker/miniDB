package top.tankenqi.zingdb.backend.parser.statement;

import java.util.List;

public class Select {
    public String tableName;
    public String[] fields;

    // 旧字段：保留兼容（少数测试与老路径仍读取）。
    // 新 Parser 在简单二元 where 时也会同步填充这里，但 Executor 优先用 expr。
    public Where where;

    // 新的 WHERE AST；为 null 表示无 WHERE。
    public Expr expr;

    // ORDER BY 字段列表；null 或空表示无排序。
    public List<OrderItem> orderBy;

    // LIMIT n / OFFSET m；< 0 表示未设置。
    public long limit = -1;
    public long offset = 0;

    // 是否为 SELECT COUNT(*)
    public boolean isCount = false;
}
