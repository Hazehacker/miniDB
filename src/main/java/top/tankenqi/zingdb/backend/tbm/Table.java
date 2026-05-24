package top.tankenqi.zingdb.backend.tbm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.primitives.Bytes;

import top.tankenqi.zingdb.backend.parser.statement.Create;
import top.tankenqi.zingdb.backend.parser.statement.Delete;
import top.tankenqi.zingdb.backend.parser.statement.Expr;
import top.tankenqi.zingdb.backend.parser.statement.Insert;
import top.tankenqi.zingdb.backend.parser.statement.OrderItem;
import top.tankenqi.zingdb.backend.parser.statement.Select;
import top.tankenqi.zingdb.backend.parser.statement.Update;
import top.tankenqi.zingdb.backend.parser.statement.Where;
import top.tankenqi.zingdb.backend.tbm.Field.ParseValueRes;
import top.tankenqi.zingdb.backend.tbm.plan.ExprEvaluator;
import top.tankenqi.zingdb.backend.tbm.plan.Planner;
import top.tankenqi.zingdb.backend.tm.TransactionManagerImpl;
import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.backend.utils.ParseStringRes;
import top.tankenqi.zingdb.backend.utils.Parser;
import top.tankenqi.zingdb.common.Error;
import top.tankenqi.zingdb.transport.ColumnType;
import top.tankenqi.zingdb.transport.ResultSet;

/**
 * Table 维护了表结构
 * 二进制结构如下：
 * [TableName][NextTable]
 * [Field1Uid][Field2Uid]...[FieldNUid]
 */
public class Table {
    TableManager tbm;
    long uid;
    String name;
    byte status;
    long nextUid;
    List<Field> fields = new ArrayList<>();

    public static Table loadTable(TableManager tbm, long uid) {
        byte[] raw = null;
        try {
            raw = ((TableManagerImpl)tbm).vm.read(TransactionManagerImpl.SUPER_XID, uid);
        } catch (Exception e) {
            Panic.panic(e);
        }
        assert raw != null;
        Table tb = new Table(tbm, uid);
        return tb.parseSelf(raw);
    }

    public static Table createTable(TableManager tbm, long nextUid, long xid, Create create) throws Exception {
        Table tb = new Table(tbm, create.tableName, nextUid);
        for(int i = 0; i < create.fieldName.length; i ++) {
            String fieldName = create.fieldName[i];
            String fieldType = create.fieldType[i];
            boolean indexed = false;
            for(int j = 0; j < create.index.length; j ++) {
                if(fieldName.equals(create.index[j])) {
                    indexed = true;
                    break;
                }
            }
            tb.fields.add(Field.createField(tb, xid, fieldName, fieldType, indexed));
        }

        return tb.persistSelf(xid);
    }

    public Table(TableManager tbm, long uid) {
        this.tbm = tbm;
        this.uid = uid;
    }

    public Table(TableManager tbm, String tableName, long nextUid) {
        this.tbm = tbm;
        this.name = tableName;
        this.nextUid = nextUid;
    }

    private Table parseSelf(byte[] raw) {
        int position = 0;
        ParseStringRes res = Parser.parseString(raw);
        name = res.str;
        position += res.next;
        nextUid = Parser.parseLong(Arrays.copyOfRange(raw, position, position+8));
        position += 8;

        while(position < raw.length) {
            long uid = Parser.parseLong(Arrays.copyOfRange(raw, position, position+8));
            position += 8;
            fields.add(Field.loadField(this, uid));
        }
        return this;
    }

    private Table persistSelf(long xid) throws Exception {
        byte[] nameRaw = Parser.string2Byte(name);
        byte[] nextRaw = Parser.long2Byte(nextUid);
        byte[] fieldRaw = new byte[0];
        for(Field field : fields) {
            fieldRaw = Bytes.concat(fieldRaw, Parser.long2Byte(field.uid));
        }
        uid = ((TableManagerImpl)tbm).vm.insert(xid, Bytes.concat(nameRaw, nextRaw, fieldRaw));
        return this;
    }

    public int delete(long xid, Delete delete) throws Exception {
        List<Long> uids = resolveCandidates(delete.expr);
        ExprEvaluator ev = new ExprEvaluator(fields);
        int count = 0;
        for (Long uid : uids) {
            byte[] raw = ((TableManagerImpl) tbm).vm.read(xid, uid);
            if (raw == null) continue;
            Map<String, Object> entry = parseEntry(raw);
            if (!ev.eval(delete.expr, entry)) continue;

            if (((TableManagerImpl) tbm).vm.delete(xid, uid)) {
                // 同步删除该行所有 indexed 字段的索引项
                for (Field f : fields) {
                    if (f.isIndexed()) {
                        Object oldVal = entry.get(f.getName());
                        if (oldVal != null) f.removeIndex(oldVal, uid);
                    }
                }
                count++;
            }
        }
        return count;
    }

    public int update(long xid, Update update) throws Exception {
        Field fd = null;
        for (Field f : fields) {
            if (f.getName().equals(update.fieldName)) { fd = f; break; }
        }
        if (fd == null) throw Error.FieldNotFoundException;
        Object newValue = fd.string2Value(update.value);

        List<Long> uids = resolveCandidates(update.expr);
        ExprEvaluator ev = new ExprEvaluator(fields);
        int count = 0;
        for (Long uid : uids) {
            byte[] raw = ((TableManagerImpl) tbm).vm.read(xid, uid);
            if (raw == null) continue;
            Map<String, Object> oldEntry = parseEntry(raw);
            if (!ev.eval(update.expr, oldEntry)) continue;

            // 老行：先删（VM 层 + 所有索引）
            ((TableManagerImpl) tbm).vm.delete(xid, uid);
            for (Field f : fields) {
                if (f.isIndexed()) {
                    Object ov = oldEntry.get(f.getName());
                    if (ov != null) f.removeIndex(ov, uid);
                }
            }

            // 新行：写入 + 重建所有索引（指向新 uid）
            Map<String, Object> newEntry = new HashMap<>(oldEntry);
            newEntry.put(fd.getName(), newValue);
            byte[] newRaw = entry2Raw(newEntry);
            long newUid = ((TableManagerImpl) tbm).vm.insert(xid, newRaw);
            for (Field f : fields) {
                if (f.isIndexed()) {
                    Object nv = newEntry.get(f.getName());
                    if (nv != null) f.insert(nv, newUid);
                }
            }
            count++;
        }
        return count;
    }

    /**
     * 旧版基于 Where 结构的简易读取，仅供旧路径与少量测试使用。新协议走 readForResultSet。
     */
    public String read(long xid, Select read) throws Exception {
        List<Long> uids = parseWhere(read.where);
        StringBuilder sb = new StringBuilder();
        for (Long uid : uids) {
            byte[] raw = ((TableManagerImpl)tbm).vm.read(xid, uid);
            if(raw == null) continue;
            Map<String, Object> entry = parseEntry(raw);
            sb.append(printEntry(entry)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 通用候选解析：
     *   - 优先使用 Expr AST（新 Planner）；
     *   - expr 为 null 时返回全表（fullScan）；
     *   - 候选集是 「超集」，需要由 ExprEvaluator 做二次过滤。
     */
    private List<Long> resolveCandidates(Expr expr) throws Exception {
        Planner planner = new Planner(fields);
        java.util.Set<Long> set = planner.plan(expr);
        return new ArrayList<>(set);
    }

    /**
     * 与 read 相同语义但返回结构化 ResultSet；走新 Planner + Evaluator 路径，
     * 支持非索引字段、嵌套 AND/OR/NOT、IN/BETWEEN/LIKE、IS NULL。
     *
     * 同时处理 SELECT 的 ORDER BY / LIMIT / OFFSET / COUNT(*)。
     */
    public ResultSet readForResultSet(long xid, Select select) throws Exception {
        // 1. 投影列
        boolean isCount = select.isCount;
        boolean isStar = select.fields.length == 1 && "*".equals(select.fields[0]);
        List<Field> projected = new ArrayList<>();
        if (!isCount) {
            if (isStar) {
                projected.addAll(fields);
            } else {
                for (String fname : select.fields) {
                    Field hit = null;
                    for (Field f : fields) {
                        if (f.getName().equals(fname)) { hit = f; break; }
                    }
                    if (hit == null) throw Error.FieldNotFoundException;
                    projected.add(hit);
                }
            }
        }

        // 2. 候选 uid + 过滤 + 收集 entry
        List<Long> uids = resolveCandidates(select.expr);
        ExprEvaluator ev = new ExprEvaluator(fields);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Long uid : uids) {
            byte[] raw = ((TableManagerImpl) tbm).vm.read(xid, uid);
            if (raw == null) continue;
            Map<String, Object> entry = parseEntry(raw);
            if (!ev.eval(select.expr, entry)) continue;
            filtered.add(entry);
        }

        // 3. COUNT(*) 提前返回
        if (isCount) {
            ResultSet rs = new ResultSet(new String[]{"count"}, new byte[]{ColumnType.INT64});
            rs.addRow(new Object[]{(long) filtered.size()});
            return rs;
        }

        // 4. ORDER BY（内存排序）
        if (select.orderBy != null && !select.orderBy.isEmpty()) {
            final List<OrderItem> ord = select.orderBy;
            filtered.sort((a, b) -> {
                for (OrderItem item : ord) {
                    Object va = a.get(item.fieldName);
                    Object vb = b.get(item.fieldName);
                    int c = nullSafeCompare(va, vb);
                    if (item.desc) c = -c;
                    if (c != 0) return c;
                }
                return 0;
            });
        }

        // 5. LIMIT / OFFSET
        long offset = Math.max(0, select.offset);
        long limit = select.limit;
        int from = (int) Math.min(offset, filtered.size());
        int to = filtered.size();
        if (limit >= 0) to = (int) Math.min(filtered.size(), from + limit);

        // 6. 投影 + 装配 ResultSet
        String[] colNames = new String[projected.size()];
        byte[] colTypes = new byte[projected.size()];
        for (int i = 0; i < projected.size(); i++) {
            colNames[i] = projected.get(i).getName();
            colTypes[i] = mapColumnType(projected.get(i).getType());
        }
        ResultSet rs = new ResultSet(colNames, colTypes);
        for (int i = from; i < to; i++) {
            Map<String, Object> entry = filtered.get(i);
            Object[] row = new Object[projected.size()];
            for (int c = 0; c < projected.size(); c++) {
                row[c] = entry.get(projected.get(c).getName());
            }
            rs.addRow(row);
        }
        return rs;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int nullSafeCompare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }
        return ((Comparable) a).compareTo(b);
    }

    private static byte mapColumnType(String fieldType) {
        switch (fieldType) {
            case "int32":    return ColumnType.INT32;
            case "int64":    return ColumnType.INT64;
            case "string":   return ColumnType.STRING;
            case "float64":  return ColumnType.FLOAT64;
            case "bool":     return ColumnType.BOOL;
            case "datetime": return ColumnType.DATETIME;
            default:         return ColumnType.STRING;
        }
    }

    public void insert(long xid, Insert insert) throws Exception {
        Map<String, Object> entry = string2Entry(insert.values);
        byte[] raw = entry2Raw(entry);
        long uid = ((TableManagerImpl)tbm).vm.insert(xid, raw);
        for (Field field : fields) {
            if(field.isIndexed()) {
                field.insert(entry.get(field.fieldName), uid);
            }
        }
    }

    private Map<String, Object> string2Entry(String[] values) throws Exception {
        if(values.length != fields.size()) {
            throw Error.InvalidValuesException;
        }
        Map<String, Object> entry = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            Object v = f.string2Value(values[i]);
            entry.put(f.fieldName, v);
        }
        return entry;
    }

    private List<Long> parseWhere(Where where) throws Exception {
        long l0=0, r0=0, l1=0, r1=0;
        boolean single = false;
        Field fd = null;
        if(where == null) {
            for (Field field : fields) {
                if(field.isIndexed()) {
                    fd = field;
                    break;
                }
            }
            l0 = 0;
            r0 = Long.MAX_VALUE;
            single = true;
        } else {
            for (Field field : fields) {
                if(field.fieldName.equals(where.singleExp1.field)) {
                    if(!field.isIndexed()) {
                        throw Error.FieldNotIndexedException;
                    }
                    fd = field;
                    break;
                }
            }
            if(fd == null) {
                throw Error.FieldNotFoundException;
            }
            CalWhereRes res = calWhere(fd, where);
            l0 = res.l0; r0 = res.r0;
            l1 = res.l1; r1 = res.r1;
            single = res.single;
        }
        List<Long> uids = fd.search(l0, r0);
        if(!single) {
            List<Long> tmp = fd.search(l1, r1);
            uids.addAll(tmp);
        }
        return uids;
    }

    class CalWhereRes {
        long l0, r0, l1, r1;
        boolean single;
    }

    private CalWhereRes calWhere(Field fd, Where where) throws Exception {
        CalWhereRes res = new CalWhereRes();
        switch(where.logicOp) {
            case "":
                res.single = true;
                FieldCalRes r = fd.calExp(where.singleExp1);
                res.l0 = r.left; res.r0 = r.right;
                break;
            case "or":
                res.single = false;
                r = fd.calExp(where.singleExp1);
                res.l0 = r.left; res.r0 = r.right;
                r = fd.calExp(where.singleExp2);
                res.l1 = r.left; res.r1 = r.right;
                break;
            case "and":
                res.single = true;
                r = fd.calExp(where.singleExp1);
                res.l0 = r.left; res.r0 = r.right;
                r = fd.calExp(where.singleExp2);
                res.l1 = r.left; res.r1 = r.right;
                if(res.l1 > res.l0) res.l0 = res.l1;
                if(res.r1 < res.r0) res.r0 = res.r1;
                break;
            default:
                throw Error.InvalidLogOpException;
        }
        return res;
    }

    private String printEntry(Map<String, Object> entry) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            sb.append(field.printValue(entry.get(field.fieldName)));
            if(i == fields.size()-1) {
                sb.append("]");
            } else {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private Map<String, Object> parseEntry(byte[] raw) {
        int pos = 0;
        Map<String, Object> entry = new HashMap<>();
        for (Field field : fields) {
            ParseValueRes r = field.parserValue(Arrays.copyOfRange(raw, pos, raw.length));
            entry.put(field.fieldName, r.v);
            pos += r.shift;
        }
        return entry;
    }

    private byte[] entry2Raw(Map<String, Object> entry) {
        byte[] raw = new byte[0];
        for (Field field : fields) {
            raw = Bytes.concat(raw, field.value2Raw(entry.get(field.fieldName)));
        }
        return raw;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        sb.append(name).append(": ");
        for(Field field : fields) {
            sb.append(field.toString());
            if(field == fields.get(fields.size()-1)) {
                sb.append("}");
            } else {
                sb.append(", ");
            }
        }
        return sb.toString();
    }
}
