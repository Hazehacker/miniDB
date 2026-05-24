package top.tankenqi.zingdb.backend.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import top.tankenqi.zingdb.backend.parser.statement.BetweenExpr;
import top.tankenqi.zingdb.backend.parser.statement.CompareExpr;
import top.tankenqi.zingdb.backend.parser.statement.Create;
import top.tankenqi.zingdb.backend.parser.statement.Delete;
import top.tankenqi.zingdb.backend.parser.statement.Desc;
import top.tankenqi.zingdb.backend.parser.statement.Drop;
import top.tankenqi.zingdb.backend.parser.statement.InExpr;
import top.tankenqi.zingdb.backend.parser.statement.Insert;
import top.tankenqi.zingdb.backend.parser.statement.LikeExpr;
import top.tankenqi.zingdb.backend.parser.statement.LogicalExpr;
import top.tankenqi.zingdb.backend.parser.statement.OrderItem;
import top.tankenqi.zingdb.backend.parser.statement.Select;
import top.tankenqi.zingdb.backend.parser.statement.Show;
import top.tankenqi.zingdb.backend.parser.statement.Update;

/**
 * 阶段 2 新增解析能力测试。
 * 目标：覆盖嵌套 AND/OR/NOT、IN/BETWEEN/LIKE/IS NULL、!=/<>/>=/<=、ORDER BY、LIMIT、COUNT、注释、大小写不敏感。
 */
public class ParserV2Test {

    private static Object parse(String sql) throws Exception {
        return Parser.Parse(sql.getBytes());
    }

    @Test
    public void caseInsensitiveKeywords() throws Exception {
        Select s = (Select) parse("SELECT id FROM Users WHERE id = 1");
        assertEquals("Users", s.tableName);     // 标识符大小写敏感
        assertTrue(s.expr instanceof CompareExpr);
    }

    @Test
    public void lineAndBlockComments() throws Exception {
        Select s = (Select) parse("select id from users where -- inline\n id > 0 /* block */");
        assertNotNull(s.expr);
    }

    @Test
    public void neqAndAltOperators() throws Exception {
        Select s1 = (Select) parse("select * from t where a != 1");
        CompareExpr c1 = (CompareExpr) s1.expr;
        assertEquals("!=", c1.op);

        Select s2 = (Select) parse("select * from t where a <> 1");
        CompareExpr c2 = (CompareExpr) s2.expr;
        assertEquals("!=", c2.op);                // <> 规范化为 !=

        Select s3 = (Select) parse("select * from t where a >= 1 and b <= 2");
        LogicalExpr l = (LogicalExpr) s3.expr;
        assertEquals(">=", ((CompareExpr) l.left).op);
        assertEquals("<=", ((CompareExpr) l.right).op);
    }

    @Test
    public void parensAndPrecedence() throws Exception {
        Select s = (Select) parse("select * from t where (a = 1 or a = 2) and b > 3");
        // 顶层应为 AND
        LogicalExpr top = (LogicalExpr) s.expr;
        assertEquals("and", top.op);
        // 左子为 OR
        assertEquals("or", ((LogicalExpr) top.left).op);
    }

    @Test
    public void notExpr() throws Exception {
        Select s = (Select) parse("select * from t where not a = 1");
        LogicalExpr l = (LogicalExpr) s.expr;
        assertEquals("not", l.op);
        assertNull(l.right);
    }

    @Test
    public void inExpr() throws Exception {
        Select s = (Select) parse("select * from t where id in (1, 2, 3)");
        InExpr e = (InExpr) s.expr;
        assertEquals(3, e.values.size());
        assertFalse(e.negated);
    }

    @Test
    public void notInExpr() throws Exception {
        Select s = (Select) parse("select * from t where id not in (1, 2)");
        InExpr e = (InExpr) s.expr;
        assertTrue(e.negated);
    }

    @Test
    public void betweenExpr() throws Exception {
        Select s = (Select) parse("select * from t where age between 18 and 30");
        BetweenExpr b = (BetweenExpr) s.expr;
        assertEquals("18", b.lo.raw);
        assertEquals("30", b.hi.raw);
        assertFalse(b.negated);
    }

    @Test
    public void likeExpr() throws Exception {
        Select s = (Select) parse("select * from t where name like 'al%'");
        LikeExpr e = (LikeExpr) s.expr;
        assertEquals("al%", e.pattern.raw);
    }

    @Test
    public void isNullExpr() throws Exception {
        Select s1 = (Select) parse("select * from t where x is null");
        CompareExpr c1 = (CompareExpr) s1.expr;
        assertEquals(CompareExpr.IS_NULL, c1.op);

        Select s2 = (Select) parse("select * from t where x is not null");
        CompareExpr c2 = (CompareExpr) s2.expr;
        assertEquals(CompareExpr.IS_NOT_NULL, c2.op);
    }

    @Test
    public void orderByAndLimit() throws Exception {
        Select s = (Select) parse("select * from t order by age desc, name limit 10 offset 5");
        assertEquals(2, s.orderBy.size());
        OrderItem o0 = s.orderBy.get(0);
        OrderItem o1 = s.orderBy.get(1);
        assertEquals("age", o0.fieldName);
        assertTrue(o0.desc);
        assertEquals("name", o1.fieldName);
        assertFalse(o1.desc);
        assertEquals(10, s.limit);
        assertEquals(5, s.offset);
    }

    @Test
    public void countStar() throws Exception {
        Select s = (Select) parse("select count(*) from t where age > 18");
        assertTrue(s.isCount);
    }

    @Test
    public void showAndDesc() throws Exception {
        assertTrue(parse("show") instanceof Show);
        assertTrue(parse("show tables") instanceof Show);
        Desc d = (Desc) parse("desc users");
        assertEquals("users", d.tableName);
        Desc d2 = (Desc) parse("describe users");
        assertEquals("users", d2.tableName);
    }

    @Test
    public void dropTable() throws Exception {
        Drop d = (Drop) parse("drop table t");
        assertEquals("t", d.tableName);
    }

    @Test
    public void insertParenSyntax() throws Exception {
        Insert ins = (Insert) parse("insert into t values (1, 'alice', 23)");
        assertEquals(3, ins.values.length);
        assertEquals("alice", ins.values[1]);
    }

    @Test
    public void updateAndDeleteWithExpr() throws Exception {
        Update u = (Update) parse("update t set name = 'x' where id in (1,2,3)");
        assertNotNull(u.expr);
        Delete d = (Delete) parse("delete from t where name like 'a%' and id > 0");
        assertNotNull(d.expr);
    }

    @Test
    public void createWithNewTypes() throws Exception {
        Create c = (Create) parse("create table m id int32, score float64, ok bool, born datetime, (index id)");
        assertEquals("m", c.tableName);
        assertEquals(4, c.fieldType.length);
        assertEquals("float64", c.fieldType[1]);
        assertEquals("bool",    c.fieldType[2]);
        assertEquals("datetime",c.fieldType[3]);
    }

    @Test
    public void invalidStmtThrows() {
        try { parse("select from t"); fail("expected error"); }
        catch (Exception ignored) {}
        try { parse("select * t");    fail("expected error"); }
        catch (Exception ignored) {}
    }

    @Test
    public void trailingSemicolonAccepted() throws Exception {
        assertTrue(parse("show;") instanceof Show);
    }

    @Test
    public void negativeLiteral() throws Exception {
        Select s = (Select) parse("select * from t where score > -3.14");
        CompareExpr c = (CompareExpr) s.expr;
        assertEquals("-3.14", c.right.raw);
    }
}
