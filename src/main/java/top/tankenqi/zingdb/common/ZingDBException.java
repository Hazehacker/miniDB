package top.tankenqi.zingdb.common;

/**
 * ZingDB 统一异常类型。
 * 携带错误码，方便客户端按类别渲染（例如不同错误码用不同颜色 / 不同 SQLSTATE）。
 *
 * 错误码按子系统分段：
 *   CM-xxxx  common
 *   DM-xxxx  data manager
 *   TM-xxxx  transaction manager
 *   VM-xxxx  version manager
 *   TB-xxxx  table manager
 *   PR-xxxx  parser
 *   TP-xxxx  transport
 *   SV-xxxx  server
 *   LC-xxxx  launcher
 */
public class ZingDBException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public ZingDBException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ZingDBException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return "[" + code + "] " + getMessage();
    }
}
