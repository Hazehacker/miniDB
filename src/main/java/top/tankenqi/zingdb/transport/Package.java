package top.tankenqi.zingdb.transport;

/**
 * 客户端/服务端之间传输的逻辑单元。一个 Package 对应一次请求或一次响应。
 *
 * 共四种形态，由 type 区分：
 *   REQUEST     —— 客户端发起的一次 SQL 文本，sql 不为空
 *   OK          —— 服务端成功响应，无结果集，message 描述操作（如 "begin" / "commit" / "create users"），
 *                  rowsAffected 可选（-1 表示不适用）
 *   RESULT_SET  —— 服务端成功响应，含一个结果集
 *   ERROR       —— 服务端失败响应，errCode + message
 *
 * elapsedNanos 由服务端在发送前回填，表示本次执行耗时（仅响应类型有意义）。
 */
public class Package {

    public static final byte TYPE_REQUEST = 0x01;
    public static final byte TYPE_OK = 0x02;
    public static final byte TYPE_RESULT_SET = 0x03;
    public static final byte TYPE_ERROR = 0x04;

    private final byte type;
    private final String sql;
    private final String message;
    private final long rowsAffected;
    private final ResultSet resultSet;
    private final String errCode;
    private long elapsedNanos;

    private Package(byte type, String sql, String message, long rowsAffected,
                    ResultSet resultSet, String errCode) {
        this.type = type;
        this.sql = sql;
        this.message = message;
        this.rowsAffected = rowsAffected;
        this.resultSet = resultSet;
        this.errCode = errCode;
    }

    // ---------- factory ----------

    public static Package request(String sql) {
        return new Package(TYPE_REQUEST, sql, null, -1, null, null);
    }

    public static Package ok(String message, long rowsAffected) {
        return new Package(TYPE_OK, null, message, rowsAffected, null, null);
    }

    public static Package resultSet(ResultSet rs) {
        return new Package(TYPE_RESULT_SET, null, null, rs.rowCount(), rs, null);
    }

    public static Package error(String errCode, String message) {
        return new Package(TYPE_ERROR, null, message, -1, null, errCode);
    }

    // ---------- getters ----------

    public byte getType() { return type; }
    public String getSql() { return sql; }
    public String getMessage() { return message; }
    public long getRowsAffected() { return rowsAffected; }
    public ResultSet getResultSet() { return resultSet; }
    public String getErrCode() { return errCode; }
    public long getElapsedNanos() { return elapsedNanos; }

    public void setElapsedNanos(long elapsedNanos) { this.elapsedNanos = elapsedNanos; }

    public boolean isError() { return type == TYPE_ERROR; }
    public boolean isResultSet() { return type == TYPE_RESULT_SET; }
    public boolean isOk() { return type == TYPE_OK; }
}
