package top.tankenqi.zingdb.transport;

/**
 * 列的逻辑类型。客户端按此值决定如何渲染列（右对齐、染色等）。
 *
 * 字节编码与服务端 Field 的内部类型一一对应，方便后续扩展（FLOAT64 / BOOL / DATETIME 等）。
 */
public final class ColumnType {
    public static final byte INT32 = 1;
    public static final byte INT64 = 2;
    public static final byte STRING = 3;
    public static final byte FLOAT64 = 4;
    public static final byte BOOL = 5;
    public static final byte DATETIME = 6;

    private ColumnType() {}

    public static String name(byte t) {
        switch (t) {
            case INT32: return "int32";
            case INT64: return "int64";
            case STRING: return "string";
            case FLOAT64: return "float64";
            case BOOL: return "bool";
            case DATETIME: return "datetime";
            default: return "unknown";
        }
    }

    public static boolean isNumeric(byte t) {
        return t == INT32 || t == INT64 || t == FLOAT64;
    }
}
