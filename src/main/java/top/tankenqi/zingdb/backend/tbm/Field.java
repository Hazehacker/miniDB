package top.tankenqi.zingdb.backend.tbm;

import java.util.Arrays;
import java.util.List;

import com.google.common.primitives.Bytes;

import top.tankenqi.zingdb.backend.im.BPlusTree;
import top.tankenqi.zingdb.backend.parser.statement.SingleExpression;
import top.tankenqi.zingdb.backend.tm.TransactionManagerImpl;
import top.tankenqi.zingdb.backend.utils.Panic;
import top.tankenqi.zingdb.backend.utils.ParseStringRes;
import top.tankenqi.zingdb.backend.utils.Parser;
import top.tankenqi.zingdb.common.Error;

/**
 * 表字段。磁盘结构：[FieldName][TypeName][IndexUid]，IndexUid==0 表示无索引。
 *
 * 阶段 2 扩展：
 *   - 类型新增 float64 / bool / datetime
 *   - value2Uid 改为类型敏感（float64 用 doubleToLongBits 排序近似，bool=>0/1，datetime=>毫秒）
 *   - string2Value 支持 "null" 字面量（返回 Java null）
 */
public class Field {
    long uid;
    private Table tb;
    String fieldName;
    String fieldType;
    private long index;
    private BPlusTree bt;

    public static Field loadField(Table tb, long uid) {
        byte[] raw = null;
        try {
            raw = ((TableManagerImpl) tb.tbm).vm.read(TransactionManagerImpl.SUPER_XID, uid);
        } catch (Exception e) {
            Panic.panic(e);
        }
        assert raw != null;
        return new Field(uid, tb).parseSelf(raw);
    }

    public Field(long uid, Table tb) {
        this.uid = uid;
        this.tb = tb;
    }

    public Field(Table tb, String fieldName, String fieldType, long index) {
        this.tb = tb;
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.index = index;
    }

    private Field parseSelf(byte[] raw) {
        int position = 0;
        ParseStringRes res = Parser.parseString(raw);
        fieldName = res.str;
        position += res.next;
        res = Parser.parseString(Arrays.copyOfRange(raw, position, raw.length));
        fieldType = res.str;
        position += res.next;
        this.index = Parser.parseLong(Arrays.copyOfRange(raw, position, position + 8));
        if (index != 0) {
            try {
                bt = BPlusTree.load(index, ((TableManagerImpl) tb.tbm).dm);
            } catch (Exception e) {
                Panic.panic(e);
            }
        }
        return this;
    }

    public static Field createField(Table tb, long xid, String fieldName, String fieldType, boolean indexed) throws Exception {
        typeCheck(fieldType);
        Field f = new Field(tb, fieldName, fieldType, 0);
        if (indexed) {
            long index = BPlusTree.create(((TableManagerImpl) tb.tbm).dm);
            BPlusTree bt = BPlusTree.load(index, ((TableManagerImpl) tb.tbm).dm);
            f.index = index;
            f.bt = bt;
        }
        f.persistSelf(xid);
        return f;
    }

    private void persistSelf(long xid) throws Exception {
        byte[] nameRaw = Parser.string2Byte(fieldName);
        byte[] typeRaw = Parser.string2Byte(fieldType);
        byte[] indexRaw = Parser.long2Byte(index);
        this.uid = ((TableManagerImpl) tb.tbm).vm.insert(xid, Bytes.concat(nameRaw, typeRaw, indexRaw));
    }

    private static void typeCheck(String fieldType) throws Exception {
        switch (fieldType) {
            case "int32":
            case "int64":
            case "string":
            case "float64":
            case "bool":
            case "datetime":
                return;
            default:
                throw Error.InvalidFieldException;
        }
    }

    public boolean isIndexed() { return index != 0; }
    public long getIndexUid() { return index; }
    public BPlusTree getBTree() { return bt; }
    public String getName() { return fieldName; }
    public String getType() { return fieldType; }

    public void insert(Object key, long uid) throws Exception {
        long uKey = value2Uid(key);
        bt.insert(uKey, uid);
    }

    /** B+ Tree 中删除 (key,uid) 对应的索引项。返回是否成功定位到该项。 */
    public boolean removeIndex(Object key, long uid) throws Exception {
        long uKey = value2Uid(key);
        return bt.delete(uKey, uid);
    }

    public List<Long> search(long left, long right) throws Exception {
        return bt.searchRange(left, right);
    }

    public Object string2Value(String str) {
        if (str == null) return null;       // 仅 Java null（来自 Literal.nullLiteral）当作 SQL NULL
        switch (fieldType) {
            case "int32":    return Integer.parseInt(str);
            case "int64":    return Long.parseLong(str);
            case "string":   return str;
            case "float64":  return Double.parseDouble(str);
            case "bool":     return parseBool(str);
            case "datetime": return parseDateTime(str);
        }
        return null;
    }

    private static Boolean parseBool(String s) {
        String t = s.trim().toLowerCase();
        if ("true".equals(t) || "1".equals(t)) return Boolean.TRUE;
        if ("false".equals(t) || "0".equals(t)) return Boolean.FALSE;
        throw new RuntimeException("invalid bool literal: " + s);
    }

    /** datetime 字面量：纯数字视为毫秒；否则尝试 ISO 格式 yyyy-MM-dd HH:mm:ss。 */
    private static Long parseDateTime(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException ignore) {}
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.parse(s).getTime();
        } catch (java.text.ParseException ignored) {}
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            return sdf.parse(s).getTime();
        } catch (java.text.ParseException ignored) {}
        throw new RuntimeException("invalid datetime literal: " + s);
    }

    /** 把值映射到 B+ Tree 的 long key。 */
    public long value2Uid(Object key) {
        if (key == null) return 0L;
        switch (fieldType) {
            case "string":
                return Parser.str2Uid((String) key);
            case "int32":
                return (long) ((Integer) key).intValue();
            case "int64":
            case "datetime":
                return ((Number) key).longValue();
            case "float64":
                // 教学型实现：用 IEEE 754 位模式做近似排序。负数排序不严格正确，但等值匹配 OK。
                return Double.doubleToLongBits(((Number) key).doubleValue());
            case "bool":
                return ((Boolean) key) ? 1L : 0L;
        }
        return 0L;
    }

    public byte[] value2Raw(Object v) {
        switch (fieldType) {
            case "int32":    return Parser.int2Byte((Integer) v);
            case "int64":    return Parser.long2Byte((Long) v);
            case "string":   return Parser.string2Byte((String) v);
            case "float64":  return Parser.long2Byte(Double.doubleToRawLongBits((Double) v));
            case "bool":     return new byte[] { (byte) (((Boolean) v) ? 1 : 0) };
            case "datetime": return Parser.long2Byte((Long) v);
        }
        return new byte[0];
    }

    public static class ParseValueRes {
        public Object v;
        public int shift;
    }

    public ParseValueRes parserValue(byte[] raw) {
        ParseValueRes res = new ParseValueRes();
        switch (fieldType) {
            case "int32":
                res.v = Parser.parseInt(Arrays.copyOf(raw, 4));
                res.shift = 4;
                break;
            case "int64":
            case "datetime":
                res.v = Parser.parseLong(Arrays.copyOf(raw, 8));
                res.shift = 8;
                break;
            case "string":
                ParseStringRes r = Parser.parseString(raw);
                res.v = r.str;
                res.shift = r.next;
                break;
            case "float64":
                long bits = Parser.parseLong(Arrays.copyOf(raw, 8));
                res.v = Double.longBitsToDouble(bits);
                res.shift = 8;
                break;
            case "bool":
                res.v = raw[0] != 0;
                res.shift = 1;
                break;
            default:
                throw new RuntimeException("unknown field type: " + fieldType);
        }
        return res;
    }

    public String printValue(Object v) {
        if (v == null) return "NULL";
        switch (fieldType) {
            case "int32":    return String.valueOf((Integer) v);
            case "int64":    return String.valueOf((Long) v);
            case "string":   return (String) v;
            case "float64":  return String.valueOf((Double) v);
            case "bool":     return String.valueOf((Boolean) v);
            case "datetime": {
                long ms = (Long) v;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                return sdf.format(new java.util.Date(ms));
            }
        }
        return String.valueOf(v);
    }

    @Override
    public String toString() {
        return new StringBuilder("(")
            .append(fieldName).append(", ").append(fieldType)
            .append(index != 0 ? ", Index" : ", NoIndex")
            .append(")").toString();
    }

    public FieldCalRes calExp(SingleExpression exp) throws Exception {
        Object v = null;
        FieldCalRes res = new FieldCalRes();
        switch (exp.compareOp) {
            case "<":
                res.left = 0;
                v = string2Value(exp.value);
                res.right = value2Uid(v);
                if (res.right > 0) res.right--;
                break;
            case "=":
                v = string2Value(exp.value);
                res.left = value2Uid(v);
                res.right = res.left;
                break;
            case ">":
                res.right = Long.MAX_VALUE;
                v = string2Value(exp.value);
                res.left = value2Uid(v) + 1;
                break;
        }
        return res;
    }
}
