package top.tankenqi.zingdb.backend.tbm.plan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.tankenqi.zingdb.backend.parser.statement.BetweenExpr;
import top.tankenqi.zingdb.backend.parser.statement.ColumnRef;
import top.tankenqi.zingdb.backend.parser.statement.CompareExpr;
import top.tankenqi.zingdb.backend.parser.statement.Expr;
import top.tankenqi.zingdb.backend.parser.statement.InExpr;
import top.tankenqi.zingdb.backend.parser.statement.LikeExpr;
import top.tankenqi.zingdb.backend.parser.statement.Literal;
import top.tankenqi.zingdb.backend.parser.statement.LogicalExpr;
import top.tankenqi.zingdb.backend.tbm.Field;

/**
 * 对单条 entry 评估 WHERE 表达式。
 *
 * 关键约定：
 *   - entry 中字段值类型已是 Java 原生类型（Integer/Long/Double/Boolean/String），
 *     由 Table.parseEntry 产出。
 *   - 字面量 raw 在此处按字段类型转换；任何字段值为 null 的比较结果为 false（除 IS NULL/IS NOT NULL）。
 *   - 不在表中的字段：抛出 RuntimeException（Parser 接受任意标识符，运行期才能确认存在）。
 */
public class ExprEvaluator {

    private final Map<String, Field> fieldByName;

    public ExprEvaluator(List<Field> fields) {
        this.fieldByName = new HashMap<>();
        for (Field f : fields) fieldByName.put(f.getName(), f);
    }

    public boolean eval(Expr expr, Map<String, Object> entry) {
        if (expr == null) return true;

        if (expr instanceof LogicalExpr) {
            LogicalExpr l = (LogicalExpr) expr;
            switch (l.op) {
                case LogicalExpr.AND: return eval(l.left, entry) && eval(l.right, entry);
                case LogicalExpr.OR:  return eval(l.left, entry) || eval(l.right, entry);
                case LogicalExpr.NOT: return !eval(l.left, entry);
                default: throw new RuntimeException("unknown logical op: " + l.op);
            }
        }
        if (expr instanceof CompareExpr) return evalCompare((CompareExpr) expr, entry);
        if (expr instanceof InExpr) return evalIn((InExpr) expr, entry);
        if (expr instanceof BetweenExpr) return evalBetween((BetweenExpr) expr, entry);
        if (expr instanceof LikeExpr) return evalLike((LikeExpr) expr, entry);

        throw new RuntimeException("unsupported expr: " + expr.getClass().getSimpleName());
    }

    private Field require(ColumnRef ref) {
        Field f = fieldByName.get(ref.name);
        if (f == null) throw new RuntimeException("field not found: " + ref.name);
        return f;
    }

    private boolean evalCompare(CompareExpr c, Map<String, Object> entry) {
        Field f = require(c.left);
        Object lv = entry.get(f.getName());

        if (CompareExpr.IS_NULL.equals(c.op))     return lv == null;
        if (CompareExpr.IS_NOT_NULL.equals(c.op)) return lv != null;

        if (lv == null) return false;
        Object rv = f.string2Value(c.right.raw);
        int cmp = compareTo(lv, rv);
        switch (c.op) {
            case CompareExpr.EQ:  return cmp == 0;
            case CompareExpr.NEQ: return cmp != 0;
            case CompareExpr.LT:  return cmp <  0;
            case CompareExpr.LE:  return cmp <= 0;
            case CompareExpr.GT:  return cmp >  0;
            case CompareExpr.GE:  return cmp >= 0;
            default: throw new RuntimeException("unknown cmp op: " + c.op);
        }
    }

    private boolean evalIn(InExpr in, Map<String, Object> entry) {
        Field f = require(in.column);
        Object lv = entry.get(f.getName());
        if (lv == null) return false;
        for (Literal lit : in.values) {
            Object rv = f.string2Value(lit.raw);
            if (compareTo(lv, rv) == 0) return !in.negated;
        }
        return in.negated;
    }

    private boolean evalBetween(BetweenExpr b, Map<String, Object> entry) {
        Field f = require(b.column);
        Object lv = entry.get(f.getName());
        if (lv == null) return false;
        Object lo = f.string2Value(b.lo.raw);
        Object hi = f.string2Value(b.hi.raw);
        boolean inRange = compareTo(lv, lo) >= 0 && compareTo(lv, hi) <= 0;
        return b.negated ? !inRange : inRange;
    }

    private boolean evalLike(LikeExpr lk, Map<String, Object> entry) {
        Field f = require(lk.column);
        Object lv = entry.get(f.getName());
        if (lv == null) return false;
        String s = String.valueOf(lv);
        String pat = lk.pattern.raw;
        boolean m = likeMatch(s, pat);
        return lk.negated ? !m : m;
    }

    /**
     * SQL LIKE 通配：% 匹配任意串（含空），_ 匹配任意单字符。
     * 实现用经典两指针 + 回溯，避免正则转义带来的边界 bug。
     */
    static boolean likeMatch(String s, String p) {
        int i = 0, j = 0, star = -1, mark = -1;
        while (i < s.length()) {
            if (j < p.length() && (p.charAt(j) == '_' || p.charAt(j) == s.charAt(i))) {
                i++; j++;
            } else if (j < p.length() && p.charAt(j) == '%') {
                star = j++;
                mark = i;
            } else if (star != -1) {
                j = star + 1;
                i = ++mark;
            } else {
                return false;
            }
        }
        while (j < p.length() && p.charAt(j) == '%') j++;
        return j == p.length();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareTo(Object a, Object b) {
        if (a == null || b == null) return a == b ? 0 : (a == null ? -1 : 1);
        if (a instanceof Number && b instanceof Number) {
            double da = ((Number) a).doubleValue();
            double db = ((Number) b).doubleValue();
            return Double.compare(da, db);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }
        return ((Comparable) a).compareTo(b);
    }
}
