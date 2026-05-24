package top.tankenqi.zingdb.backend.parser.statement;

/** ORDER BY 单个字段。 */
public class OrderItem {
    public final String fieldName;
    public final boolean desc;

    public OrderItem(String fieldName, boolean desc) {
        this.fieldName = fieldName;
        this.desc = desc;
    }
}
