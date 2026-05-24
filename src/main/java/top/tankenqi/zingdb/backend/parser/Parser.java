package top.tankenqi.zingdb.backend.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import top.tankenqi.zingdb.backend.parser.statement.Abort;
import top.tankenqi.zingdb.backend.parser.statement.Begin;
import top.tankenqi.zingdb.backend.parser.statement.BetweenExpr;
import top.tankenqi.zingdb.backend.parser.statement.ColumnRef;
import top.tankenqi.zingdb.backend.parser.statement.Commit;
import top.tankenqi.zingdb.backend.parser.statement.CompareExpr;
import top.tankenqi.zingdb.backend.parser.statement.Create;
import top.tankenqi.zingdb.backend.parser.statement.Delete;
import top.tankenqi.zingdb.backend.parser.statement.Desc;
import top.tankenqi.zingdb.backend.parser.statement.Drop;
import top.tankenqi.zingdb.backend.parser.statement.Expr;
import top.tankenqi.zingdb.backend.parser.statement.InExpr;
import top.tankenqi.zingdb.backend.parser.statement.Insert;
import top.tankenqi.zingdb.backend.parser.statement.LikeExpr;
import top.tankenqi.zingdb.backend.parser.statement.Literal;
import top.tankenqi.zingdb.backend.parser.statement.LogicalExpr;
import top.tankenqi.zingdb.backend.parser.statement.OrderItem;
import top.tankenqi.zingdb.backend.parser.statement.Select;
import top.tankenqi.zingdb.backend.parser.statement.Show;
import top.tankenqi.zingdb.backend.parser.statement.SingleExpression;
import top.tankenqi.zingdb.backend.parser.statement.Stats;
import top.tankenqi.zingdb.backend.parser.statement.Update;
import top.tankenqi.zingdb.backend.parser.statement.Where;
import top.tankenqi.zingdb.common.Error;

/**
 * 递归下降 SQL 解析器。
 *
 * 语法摘要（关键字大小写不敏感）：
 *
 *   stmt := begin | commit | abort
 *         | create_table | drop_table | desc
 *         | select | insert | delete | update
 *         | show
 *
 *   select := SELECT (* | COUNT '(' '*' ')' | field_list) FROM ident [where] [order_by] [limit]
 *   where  := WHERE expr_or
 *   expr_or  := expr_and (OR expr_and)*
 *   expr_and := expr_not (AND expr_not)*
 *   expr_not := NOT expr_not | expr_pred
 *   expr_pred := '(' expr_or ')' | predicate
 *   predicate := col IS [NOT] NULL
 *              | col [NOT] BETWEEN lit AND lit
 *              | col [NOT] IN '(' lit (',' lit)* ')'
 *              | col [NOT] LIKE lit
 *              | col compare lit
 *   compare := = | != | <> | < | <= | > | >=
 *
 *   order_by := ORDER BY ident [ASC|DESC] (',' ident [ASC|DESC])*
 *   limit    := LIMIT number [OFFSET number]
 *
 * 字面量包括：数字（含负号一元）、字符串、true/false、null
 */
public class Parser {

    public static Object Parse(byte[] statement) throws Exception {
        Tokenizer tk = new Tokenizer(statement);
        String head = lower(tk.peek());
        tk.pop();
        Object stat;
        Exception statErr = null;
        try {
            switch (head) {
                case "begin":  stat = parseBegin(tk); break;
                case "commit": stat = parseCommit(tk); break;
                case "abort":  stat = parseAbort(tk); break;
                case "create": stat = parseCreate(tk); break;
                case "drop":   stat = parseDrop(tk); break;
                case "select": stat = parseSelect(tk); break;
                case "insert": stat = parseInsert(tk); break;
                case "delete": stat = parseDelete(tk); break;
                case "update": stat = parseUpdate(tk); break;
                case "show":   stat = parseShow(tk); break;
                case "desc":
                case "describe": stat = parseDesc(tk); break;
                default: throw Error.InvalidCommandException;
            }
        } catch (Exception e) {
            statErr = e;
            stat = null;
        }
        try {
            String next = tk.peek();
            // 允许末尾的 ';'
            if (";".equals(next)) { tk.pop(); next = tk.peek(); }
            if (!"".equals(next)) {
                byte[] errStat = tk.errStat();
                statErr = new RuntimeException("Invalid statement: " + new String(errStat));
            }
        } catch (Exception e) {
            byte[] errStat = tk.errStat();
            statErr = new RuntimeException("Invalid statement: " + new String(errStat));
        }
        if (statErr != null) throw statErr;
        return stat;
    }

    // ===================== DDL =====================

    private static Desc parseDesc(Tokenizer tk) throws Exception {
        String name = tk.peek();
        if (!isName(name)) throw Error.InvalidCommandException;
        tk.pop();
        Desc d = new Desc();
        d.tableName = name;
        return d;
    }

    private static Object parseShow(Tokenizer tk) throws Exception {
        // SHOW           -> Show
        // SHOW TABLES    -> Show
        // SHOW STATS     -> Stats
        String next = lower(tk.peek());
        if ("stats".equals(next)) { tk.pop(); return new Stats(); }
        if ("tables".equals(next)) tk.pop();
        return new Show();
    }

    private static Drop parseDrop(Tokenizer tk) throws Exception {
        if (!"table".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();
        String name = tk.peek();
        if (!isName(name)) throw Error.InvalidCommandException;
        tk.pop();
        Drop d = new Drop();
        d.tableName = name;
        return d;
    }

    private static Create parseCreate(Tokenizer tk) throws Exception {
        if (!"table".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();
        Create create = new Create();
        String name = tk.peek();
        if (!isName(name)) throw Error.InvalidCommandException;
        create.tableName = name;

        List<String> fNames = new ArrayList<>();
        List<String> fTypes = new ArrayList<>();
        while (true) {
            tk.pop();
            String field = tk.peek();
            if ("(".equals(field)) break;
            if (!isName(field)) throw Error.InvalidCommandException;
            tk.pop();
            String fieldType = lower(tk.peek());
            if (!isType(fieldType)) throw Error.InvalidCommandException;
            fNames.add(field);
            fTypes.add(fieldType);
            tk.pop();
            String next = tk.peek();
            if (",".equals(next)) continue;
            if ("".equals(next)) throw Error.TableNoIndexException;
            if ("(".equals(next)) break;
            throw Error.InvalidCommandException;
        }
        create.fieldName = fNames.toArray(new String[0]);
        create.fieldType = fTypes.toArray(new String[0]);

        tk.pop();
        if (!"index".equals(lower(tk.peek()))) throw Error.InvalidCommandException;

        List<String> indexes = new ArrayList<>();
        while (true) {
            tk.pop();
            String field = tk.peek();
            if (")".equals(field)) break;
            if (!isName(field)) throw Error.InvalidCommandException;
            indexes.add(field);
        }
        create.index = indexes.toArray(new String[0]);
        tk.pop();
        return create;
    }

    // ===================== DML =====================

    private static Select parseSelect(Tokenizer tk) throws Exception {
        Select sel = new Select();
        List<String> fields = new ArrayList<>();
        String head = tk.peek();

        if ("*".equals(head)) {
            fields.add("*");
            tk.pop();
        } else if ("count".equalsIgnoreCase(head)) {
            tk.pop();
            if (!"(".equals(tk.peek())) throw Error.InvalidCommandException;
            tk.pop();
            if (!"*".equals(tk.peek())) throw Error.InvalidCommandException;
            tk.pop();
            if (!")".equals(tk.peek())) throw Error.InvalidCommandException;
            tk.pop();
            sel.isCount = true;
            fields.add("count");
        } else {
            while (true) {
                String f = tk.peek();
                if (!isName(f)) throw Error.InvalidCommandException;
                fields.add(f);
                tk.pop();
                if (",".equals(tk.peek())) { tk.pop(); continue; }
                break;
            }
        }
        sel.fields = fields.toArray(new String[0]);

        if (!"from".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();
        String tableName = tk.peek();
        if (!isName(tableName)) throw Error.InvalidCommandException;
        sel.tableName = tableName;
        tk.pop();

        if ("where".equals(lower(tk.peek()))) {
            tk.pop();
            sel.expr = parseExprOr(tk);
            sel.where = exprToLegacyWhere(sel.expr);
        }

        if ("order".equals(lower(tk.peek()))) {
            tk.pop();
            if (!"by".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
            tk.pop();
            List<OrderItem> ords = new ArrayList<>();
            while (true) {
                String col = tk.peek();
                if (!isName(col)) throw Error.InvalidCommandException;
                tk.pop();
                boolean desc = false;
                String dir = lower(tk.peek());
                if ("asc".equals(dir)) { desc = false; tk.pop(); }
                else if ("desc".equals(dir)) { desc = true; tk.pop(); }
                ords.add(new OrderItem(col, desc));
                if (",".equals(tk.peek())) { tk.pop(); continue; }
                break;
            }
            sel.orderBy = ords;
        }

        if ("limit".equals(lower(tk.peek()))) {
            tk.pop();
            String n = tk.peek();
            tk.pop();
            try { sel.limit = Long.parseLong(n); }
            catch (NumberFormatException e) { throw Error.InvalidCommandException; }
            if ("offset".equals(lower(tk.peek()))) {
                tk.pop();
                String m = tk.peek();
                tk.pop();
                try { sel.offset = Long.parseLong(m); }
                catch (NumberFormatException e) { throw Error.InvalidCommandException; }
            }
        }

        return sel;
    }

    private static Insert parseInsert(Tokenizer tk) throws Exception {
        Insert ins = new Insert();
        if (!"into".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();
        String tableName = tk.peek();
        if (!isName(tableName)) throw Error.InvalidCommandException;
        ins.tableName = tableName;
        tk.pop();
        if (!"values".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();

        // 兼容两种写法：
        //   1) VALUES v1 v2 v3            （旧）
        //   2) VALUES (v1, v2, v3)        （新）
        List<String> values = new ArrayList<>();
        if ("(".equals(tk.peek())) {
            tk.pop();
            while (true) {
                String v = readLiteralToken(tk);
                values.add(v);
                if (",".equals(tk.peek())) { tk.pop(); continue; }
                break;
            }
            if (!")".equals(tk.peek())) throw Error.InvalidCommandException;
            tk.pop();
        } else {
            while (true) {
                String v = tk.peek();
                if ("".equals(v)) break;
                values.add(v);
                tk.pop();
            }
        }
        ins.values = values.toArray(new String[0]);
        return ins;
    }

    private static Delete parseDelete(Tokenizer tk) throws Exception {
        if (!"from".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();
        String tableName = tk.peek();
        if (!isName(tableName)) throw Error.InvalidCommandException;
        tk.pop();
        Delete d = new Delete();
        d.tableName = tableName;
        if ("where".equals(lower(tk.peek()))) {
            tk.pop();
            d.expr = parseExprOr(tk);
            d.where = exprToLegacyWhere(d.expr);
        }
        return d;
    }

    private static Update parseUpdate(Tokenizer tk) throws Exception {
        Update up = new Update();
        up.tableName = tk.peek();
        tk.pop();
        if (!"set".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();
        up.fieldName = tk.peek();
        tk.pop();
        if (!"=".equals(tk.peek())) throw Error.InvalidCommandException;
        tk.pop();
        up.value = readLiteralToken(tk);
        if ("where".equals(lower(tk.peek()))) {
            tk.pop();
            up.expr = parseExprOr(tk);
            up.where = exprToLegacyWhere(up.expr);
        }
        return up;
    }

    // ===================== TX =====================

    private static Begin parseBegin(Tokenizer tk) throws Exception {
        String isolation = lower(tk.peek());
        Begin begin = new Begin();
        if ("".equals(isolation)) return begin;
        if (!"isolation".equals(isolation)) throw Error.InvalidCommandException;
        tk.pop();
        if (!"level".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
        tk.pop();
        String w1 = lower(tk.peek());
        if ("read".equals(w1)) {
            tk.pop();
            if (!"committed".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
            tk.pop();
            return begin;
        } else if ("repeatable".equals(w1)) {
            tk.pop();
            if (!"read".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
            begin.isRepeatableRead = true;
            tk.pop();
            return begin;
        } else {
            throw Error.InvalidCommandException;
        }
    }

    private static Commit parseCommit(Tokenizer tk) { return new Commit(); }
    private static Abort parseAbort(Tokenizer tk) { return new Abort(); }

    // ===================== Expressions =====================

    private static Expr parseExprOr(Tokenizer tk) throws Exception {
        Expr left = parseExprAnd(tk);
        while ("or".equals(lower(tk.peek()))) {
            tk.pop();
            Expr right = parseExprAnd(tk);
            left = new LogicalExpr(LogicalExpr.OR, left, right);
        }
        return left;
    }

    private static Expr parseExprAnd(Tokenizer tk) throws Exception {
        Expr left = parseExprNot(tk);
        while ("and".equals(lower(tk.peek()))) {
            tk.pop();
            Expr right = parseExprNot(tk);
            left = new LogicalExpr(LogicalExpr.AND, left, right);
        }
        return left;
    }

    private static Expr parseExprNot(Tokenizer tk) throws Exception {
        if ("not".equals(lower(tk.peek()))) {
            tk.pop();
            Expr inner = parseExprNot(tk);
            return new LogicalExpr(LogicalExpr.NOT, inner, null);
        }
        return parseExprPred(tk);
    }

    private static Expr parseExprPred(Tokenizer tk) throws Exception {
        if ("(".equals(tk.peek())) {
            tk.pop();
            Expr inner = parseExprOr(tk);
            if (!")".equals(tk.peek())) throw Error.InvalidCommandException;
            tk.pop();
            return inner;
        }
        // 左侧必须是列名
        String col = tk.peek();
        if (!isName(col)) throw Error.InvalidCommandException;
        tk.pop();
        ColumnRef colRef = new ColumnRef(col);

        String op = lower(tk.peek());

        // IS [NOT] NULL
        if ("is".equals(op)) {
            tk.pop();
            boolean negated = false;
            if ("not".equals(lower(tk.peek()))) { negated = true; tk.pop(); }
            if (!"null".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
            tk.pop();
            return new CompareExpr(negated ? CompareExpr.IS_NOT_NULL : CompareExpr.IS_NULL, colRef, null);
        }

        boolean negated = false;
        if ("not".equals(op)) {
            tk.pop();
            negated = true;
            op = lower(tk.peek());
        }

        // BETWEEN
        if ("between".equals(op)) {
            tk.pop();
            Literal lo = parseLiteral(tk);
            if (!"and".equals(lower(tk.peek()))) throw Error.InvalidCommandException;
            tk.pop();
            Literal hi = parseLiteral(tk);
            return new BetweenExpr(colRef, lo, hi, negated);
        }

        // IN
        if ("in".equals(op)) {
            tk.pop();
            if (!"(".equals(tk.peek())) throw Error.InvalidCommandException;
            tk.pop();
            List<Literal> vals = new ArrayList<>();
            while (true) {
                vals.add(parseLiteral(tk));
                if (",".equals(tk.peek())) { tk.pop(); continue; }
                break;
            }
            if (!")".equals(tk.peek())) throw Error.InvalidCommandException;
            tk.pop();
            return new InExpr(colRef, vals, negated);
        }

        // LIKE
        if ("like".equals(op)) {
            tk.pop();
            Literal pat = parseLiteral(tk);
            return new LikeExpr(colRef, pat, negated);
        }

        if (negated) {
            // NOT 后面只能跟 BETWEEN / IN / LIKE
            throw Error.InvalidCommandException;
        }

        // 比较运算
        if (!isCmpOp(op)) throw Error.InvalidCommandException;
        tk.pop();
        Literal rhs = parseLiteral(tk);
        String normOp;
        if ("<>".equals(op)) normOp = "!=";
        else normOp = op;
        return new CompareExpr(normOp, colRef, rhs);
    }

    private static Literal parseLiteral(Tokenizer tk) throws Exception {
        String v = tk.peek();
        if ("".equals(v)) throw Error.InvalidCommandException;
        // 一元负号
        if ("-".equals(v)) {
            tk.pop();
            String num = tk.peek();
            tk.pop();
            return new Literal("-" + num);
        }
        tk.pop();
        if ("null".equalsIgnoreCase(v)) return Literal.nullLiteral();
        return new Literal(v);
    }

    /** 读取一个字面量 token（用于 insert/update 的 value 位置，含 - 号）。 */
    private static String readLiteralToken(Tokenizer tk) throws Exception {
        String v = tk.peek();
        if ("-".equals(v)) {
            tk.pop();
            String num = tk.peek();
            tk.pop();
            return "-" + num;
        }
        tk.pop();
        return v;
    }

    // ===================== Helpers =====================

    private static boolean isType(String tp) {
        return "int32".equals(tp) || "int64".equals(tp) || "string".equals(tp)
            || "float64".equals(tp) || "bool".equals(tp) || "datetime".equals(tp);
    }

    private static boolean isCmpOp(String op) {
        return "=".equals(op) || "!=".equals(op) || "<>".equals(op)
            || ">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op);
    }

    private static boolean isName(String name) {
        if (name == null || name.length() == 0) return false;
        // 关键字 / 符号不能作为标识符
        if (name.length() == 1 && !Tokenizer.isAlphaBeta(name.getBytes()[0]) && name.charAt(0) != '_') return false;
        // 排除保留字
        String low = name.toLowerCase(Locale.ROOT);
        switch (low) {
            case "and": case "or": case "not": case "is": case "null":
            case "in": case "between": case "like": case "where":
            case "from": case "into": case "values": case "set":
            case "select": case "insert": case "delete": case "update":
            case "create": case "drop": case "table": case "index":
            case "begin": case "commit": case "abort": case "isolation": case "level":
            case "read": case "committed": case "repeatable":
            case "show": case "desc": case "describe":
            case "stats":
            case "order": case "by": case "asc": case "limit": case "offset":
            case "count":
                return false;
        }
        return true;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * 把新 Expr 退化成旧 Where 结构以兼容旧代码（仅当 expr 是单 CompareExpr
     * 或 AND/OR 两个 CompareExpr 时填充；否则置 null —— 旧路径将无法表达）。
     */
    private static Where exprToLegacyWhere(Expr e) {
        if (e == null) return null;
        if (e instanceof CompareExpr) {
            CompareExpr c = (CompareExpr) e;
            if (c.right == null) return null;
            Where w = new Where();
            w.singleExp1 = toSE(c);
            w.logicOp = "";
            return w;
        }
        if (e instanceof LogicalExpr) {
            LogicalExpr l = (LogicalExpr) e;
            if (!LogicalExpr.NOT.equals(l.op)
                && l.left instanceof CompareExpr && l.right instanceof CompareExpr) {
                CompareExpr lc = (CompareExpr) l.left;
                CompareExpr rc = (CompareExpr) l.right;
                if (lc.right == null || rc.right == null) return null;
                Where w = new Where();
                w.singleExp1 = toSE(lc);
                w.logicOp = l.op;
                w.singleExp2 = toSE(rc);
                return w;
            }
        }
        return null;
    }

    private static SingleExpression toSE(CompareExpr c) {
        SingleExpression se = new SingleExpression();
        se.field = c.left.name;
        se.compareOp = c.op;
        se.value = c.right.isNull ? null : c.right.raw;
        return se;
    }
}
