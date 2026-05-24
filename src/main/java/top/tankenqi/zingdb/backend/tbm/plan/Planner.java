package top.tankenqi.zingdb.backend.tbm.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.tankenqi.zingdb.backend.parser.statement.BetweenExpr;
import top.tankenqi.zingdb.backend.parser.statement.ColumnRef;
import top.tankenqi.zingdb.backend.parser.statement.CompareExpr;
import top.tankenqi.zingdb.backend.parser.statement.Expr;
import top.tankenqi.zingdb.backend.parser.statement.InExpr;
import top.tankenqi.zingdb.backend.parser.statement.Literal;
import top.tankenqi.zingdb.backend.parser.statement.LogicalExpr;
import top.tankenqi.zingdb.backend.tbm.Field;

/**
 * 候选 uid 集合规划器。
 *
 * 核心职责：给定 WHERE 表达式，产出一个**候选 uid 集合**（行 id），后续由调用方做
 * 「读 entry → ExprEvaluator 二次过滤」。
 *
 * 这种「候选 + 二次过滤」的设计回答了三个老 bug：
 *   1. 非索引字段：直接返回全表（fullScan via 任一索引字段 [0, MAX]）。
 *   2. AND/OR：CompareExpr 各自走索引拿 uid 集合后做交/并/差，去重。
 *   3. 复杂表达式（IN/BETWEEN/LIKE/IS NULL/NOT/嵌套括号）：Planner 兜底为 fullScan；
 *      正确性由 ExprEvaluator 保证，索引仅作为加速。
 *
 * 简化但正确的策略：
 *   - 只对**索引字段 + 简单比较 / IN / BETWEEN** 走 index 路径，其余一律 fullScan；
 *   - AND：两侧的 candidates 取交集；
 *   - OR：取并集；
 *   - NOT：直接退化为 fullScan（让 evaluator 过滤）；
 *   - 任意子表达式无法精准缩小范围时 → fullScan。
 */
public class Planner {

    private final List<Field> fields;
    private final Map<String, Field> byName;

    public Planner(List<Field> fields) {
        this.fields = fields;
        this.byName = new HashMap<>();
        for (Field f : fields) byName.put(f.getName(), f);
    }

    /** 选一个索引字段，用于全表扫描兜底。优先返回首个索引字段，没有就 null。 */
    private Field anyIndexed() {
        for (Field f : fields) if (f.isIndexed()) return f;
        return null;
    }

    /** 全表扫描：用任一索引字段做 [0, MAX] range search。无任何索引时返回 null。 */
    public Set<Long> fullScan() throws Exception {
        Field f = anyIndexed();
        if (f == null) return new LinkedHashSet<>();
        List<Long> uids = f.search(0, Long.MAX_VALUE);
        return new LinkedHashSet<>(uids);
    }

    /**
     * 计算候选集。返回的集合是「**超集**」：里面的 uid 一定包含所有满足 expr 的行，
     * 但也可能包含不满足的（需要 ExprEvaluator 二次过滤）。
     *
     * 返回 null 表示「无法用 index 收敛」，调用方应使用 fullScan()。
     */
    public Set<Long> plan(Expr expr) throws Exception {
        if (expr == null) return fullScan();
        Set<Long> s = planOrNull(expr);
        if (s != null) return s;
        return fullScan();
    }

    /** 内部：能用 index 收敛返回集合；否则返回 null（由 plan 兜底为 fullScan）。 */
    private Set<Long> planOrNull(Expr expr) throws Exception {
        if (expr instanceof LogicalExpr) {
            LogicalExpr l = (LogicalExpr) expr;
            if (LogicalExpr.NOT.equals(l.op)) return null;
            Set<Long> ls = planOrNull(l.left);
            Set<Long> rs = planOrNull(l.right);
            if (LogicalExpr.AND.equals(l.op)) {
                // AND：哪边能精确就用哪边收敛；都能精确就求交
                if (ls == null && rs == null) return null;
                if (ls == null) return rs;
                if (rs == null) return ls;
                Set<Long> out = new LinkedHashSet<>(ls);
                out.retainAll(rs);
                return out;
            }
            if (LogicalExpr.OR.equals(l.op)) {
                // OR：任一边无法精确则只能 fullScan
                if (ls == null || rs == null) return null;
                Set<Long> out = new LinkedHashSet<>(ls);
                out.addAll(rs);
                return out;
            }
            return null;
        }
        if (expr instanceof CompareExpr) return planCompare((CompareExpr) expr);
        if (expr instanceof InExpr)      return planIn((InExpr) expr);
        if (expr instanceof BetweenExpr) return planBetween((BetweenExpr) expr);
        // LikeExpr / IS NULL: 无法收敛
        return null;
    }

    private Set<Long> planCompare(CompareExpr c) throws Exception {
        if (c.right == null) return null; // IS NULL / IS NOT NULL
        Field f = byName.get(c.left.name);
        if (f == null || !f.isIndexed()) return null;
        Object v = f.string2Value(c.right.raw);
        if (v == null) return null;
        long key = f.value2Uid(v);
        switch (c.op) {
            case CompareExpr.EQ:
                return toSet(f.search(key, key));
            case CompareExpr.LT:
                if (key == 0) return new LinkedHashSet<>();
                return toSet(f.search(0, key - 1));
            case CompareExpr.LE:
                return toSet(f.search(0, key));
            case CompareExpr.GT:
                if (key == Long.MAX_VALUE) return new LinkedHashSet<>();
                return toSet(f.search(key + 1, Long.MAX_VALUE));
            case CompareExpr.GE:
                return toSet(f.search(key, Long.MAX_VALUE));
            case CompareExpr.NEQ:
                // != 无法用索引精确收敛
                return null;
            default:
                return null;
        }
    }

    private Set<Long> planIn(InExpr in) throws Exception {
        Field f = byName.get(in.column.name);
        if (f == null || !f.isIndexed() || in.negated) return null;
        Set<Long> out = new LinkedHashSet<>();
        for (Literal lit : in.values) {
            Object v = f.string2Value(lit.raw);
            if (v == null) continue;
            long key = f.value2Uid(v);
            out.addAll(f.search(key, key));
        }
        return out;
    }

    private Set<Long> planBetween(BetweenExpr b) throws Exception {
        Field f = byName.get(b.column.name);
        if (f == null || !f.isIndexed() || b.negated) return null;
        Object lo = f.string2Value(b.lo.raw);
        Object hi = f.string2Value(b.hi.raw);
        if (lo == null || hi == null) return null;
        long klo = f.value2Uid(lo);
        long khi = f.value2Uid(hi);
        if (klo > khi) { long t = klo; klo = khi; khi = t; }
        return toSet(f.search(klo, khi));
    }

    private static Set<Long> toSet(List<Long> list) {
        return new LinkedHashSet<>(list);
    }

    /** 工具：暴露给调用方的「读 entry 后做 evaluator 过滤」常需的工具。 */
    public static List<Long> dedupOrdered(List<Long> input) {
        Set<Long> seen = new HashSet<>();
        List<Long> out = new ArrayList<>(input.size());
        for (Long x : input) if (seen.add(x)) out.add(x);
        return out;
    }

    @SuppressWarnings("unused")
    private static ColumnRef _silenceUnused() { return null; }
}
