package top.tankenqi.zingdb.backend.parser.statement;

/**
 * WHERE 表达式 AST 的根接口。
 *
 * 全部使用具体实现类（不要抽象），结构尽量贴近字面意义，方便：
 *   1. Planner 模式匹配（INDEX scan / TABLE scan / 集合运算）
 *   2. ExprEvaluator 逐节点求值
 *   3. 测试与日志直接打印结构
 *
 * 五种节点：
 *   Logical(AND/OR/NOT)
 *   Compare(=  !=  <>  <  <=  >  >=  IS NULL  IS NOT NULL)
 *   InExpr (col IN (v1, v2, ...))
 *   BetweenExpr (col BETWEEN a AND b)
 *   LikeExpr (col LIKE 'pattern')      —— 仅 string 字段，支持 % 与 _
 *
 * 字面量与列引用：
 *   ColumnRef.name
 *   Literal.raw   —— 原始字符串，由 Field.string2Value 在运行时按字段类型转换
 *                    （这样可以兼容 int32/int64/float64/bool/datetime/string）
 */
public interface Expr {
    /** 显示用，便于 debug。 */
    String repr();
}
