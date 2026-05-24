package top.tankenqi.zingdb.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构化结果集。由服务端组装，序列化后发送给客户端，由客户端按列对齐渲染表格。
 *
 * 行数据使用 Object[]，元素类型按 columnTypes 解读：
 *   INT32    -> Integer (可为 null)
 *   INT64    -> Long
 *   STRING   -> String
 *   FLOAT64  -> Double
 *   BOOL     -> Boolean
 *   DATETIME -> Long (毫秒)
 *   NULL     -> null
 */
public class ResultSet {
    private final String[] columnNames;
    private final byte[] columnTypes;
    private final List<Object[]> rows;

    /** 可选的统计信息，渲染在表格下方，例如 "index scan on age"。null 表示无。 */
    private String note;

    public ResultSet(String[] columnNames, byte[] columnTypes) {
        if (columnNames.length != columnTypes.length) {
            throw new IllegalArgumentException("columnNames.length != columnTypes.length");
        }
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
        this.rows = new ArrayList<>();
    }

    public void addRow(Object[] row) {
        if (row.length != columnNames.length) {
            throw new IllegalArgumentException("row width mismatch");
        }
        rows.add(row);
    }

    public String[] getColumnNames() {
        return columnNames;
    }

    public byte[] getColumnTypes() {
        return columnTypes;
    }

    public List<Object[]> getRows() {
        return Collections.unmodifiableList(rows);
    }

    public int rowCount() {
        return rows.size();
    }

    public int columnCount() {
        return columnNames.length;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
