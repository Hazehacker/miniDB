package top.tankenqi.zingdb.transport;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import top.tankenqi.zingdb.common.Error;

/**
 * 二进制编解码器。负责把 Package 与字节流相互转换。
 *
 * 帧 payload 布局（不含 Transporter 的 magic / type / len 头）：
 *
 *   REQUEST    : [sqlLen:4][sql:utf8]
 *
 *   OK         : [rowsAffected:8][elapsedNanos:8][msgLen:4][msg:utf8]
 *
 *   RESULT_SET : [colCount:4]
 *                {[nameLen:4][name:utf8][type:1]} * colCount
 *                [rowCount:4]
 *                {  nullBitmap:ceil(colCount/8) bytes
 *                   { value按列类型编码 } * (非NULL列)
 *                } * rowCount
 *                [noteLen:4][note:utf8]    // 可空，noteLen=0 表示无
 *                [elapsedNanos:8]
 *
 *   ERROR      : [codeLen:4][code:utf8][msgLen:4][msg:utf8]
 *
 * 各 value 的编码：
 *   INT32    -> 4 bytes
 *   INT64    -> 8 bytes
 *   FLOAT64  -> 8 bytes (IEEE 754)
 *   BOOL     -> 1 byte
 *   DATETIME -> 8 bytes (毫秒)
 *   STRING   -> [len:4][bytes:utf8]
 */
public class Encoder {

    public byte[] encode(Package pkg) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            switch (pkg.getType()) {
                case Package.TYPE_REQUEST:
                    writeString(out, nullToEmpty(pkg.getSql()));
                    break;
                case Package.TYPE_OK:
                    out.writeLong(pkg.getRowsAffected());
                    out.writeLong(pkg.getElapsedNanos());
                    writeString(out, nullToEmpty(pkg.getMessage()));
                    break;
                case Package.TYPE_RESULT_SET:
                    encodeResultSet(out, pkg);
                    break;
                case Package.TYPE_ERROR:
                    writeString(out, nullToEmpty(pkg.getErrCode()));
                    writeString(out, nullToEmpty(pkg.getMessage()));
                    break;
                default:
                    throw new RuntimeException("[TP-0001] Invalid package data!");
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("encode failed", e);
        }
    }

    public Package decode(byte type, byte[] payload) throws Exception {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        switch (type) {
            case Package.TYPE_REQUEST: {
                String sql = readString(in);
                return Package.request(sql);
            }
            case Package.TYPE_OK: {
                long rows = in.readLong();
                long elapsed = in.readLong();
                String msg = readString(in);
                Package pkg = Package.ok(msg, rows);
                pkg.setElapsedNanos(elapsed);
                return pkg;
            }
            case Package.TYPE_RESULT_SET: {
                return decodeResultSet(in);
            }
            case Package.TYPE_ERROR: {
                String code = readString(in);
                String msg = readString(in);
                return Package.error(code, msg);
            }
            default:
                throw Error.InvalidPkgDataException;
        }
    }

    // ---------- helpers ----------

    private void encodeResultSet(DataOutputStream out, Package pkg) throws IOException {
        ResultSet rs = pkg.getResultSet();
        String[] names = rs.getColumnNames();
        byte[] types = rs.getColumnTypes();
        int colCount = names.length;
        out.writeInt(colCount);
        for (int i = 0; i < colCount; i++) {
            writeString(out, names[i]);
            out.writeByte(types[i]);
        }
        out.writeInt(rs.rowCount());
        int bitmapBytes = (colCount + 7) >>> 3;
        for (Object[] row : rs.getRows()) {
            byte[] bitmap = new byte[bitmapBytes];
            for (int c = 0; c < colCount; c++) {
                if (row[c] == null) {
                    bitmap[c >>> 3] |= (byte) (1 << (c & 7));
                }
            }
            out.write(bitmap);
            for (int c = 0; c < colCount; c++) {
                if (row[c] == null) continue;
                writeValue(out, types[c], row[c]);
            }
        }
        writeString(out, nullToEmpty(rs.getNote()));
        out.writeLong(pkg.getElapsedNanos());
    }

    private Package decodeResultSet(DataInputStream in) throws IOException {
        int colCount = in.readInt();
        String[] names = new String[colCount];
        byte[] types = new byte[colCount];
        for (int i = 0; i < colCount; i++) {
            names[i] = readString(in);
            types[i] = in.readByte();
        }
        ResultSet rs = new ResultSet(names, types);
        int rowCount = in.readInt();
        int bitmapBytes = (colCount + 7) >>> 3;
        for (int r = 0; r < rowCount; r++) {
            byte[] bitmap = new byte[bitmapBytes];
            in.readFully(bitmap);
            Object[] row = new Object[colCount];
            for (int c = 0; c < colCount; c++) {
                boolean isNull = (bitmap[c >>> 3] & (1 << (c & 7))) != 0;
                row[c] = isNull ? null : readValue(in, types[c]);
            }
            rs.addRow(row);
        }
        String note = readString(in);
        if (note != null && !note.isEmpty()) rs.setNote(note);
        long elapsed = in.readLong();
        Package pkg = Package.resultSet(rs);
        pkg.setElapsedNanos(elapsed);
        return pkg;
    }

    private void writeValue(DataOutputStream out, byte type, Object v) throws IOException {
        switch (type) {
            case ColumnType.INT32:
                out.writeInt(((Number) v).intValue());
                break;
            case ColumnType.INT64:
            case ColumnType.DATETIME:
                out.writeLong(((Number) v).longValue());
                break;
            case ColumnType.FLOAT64:
                out.writeDouble(((Number) v).doubleValue());
                break;
            case ColumnType.BOOL:
                out.writeBoolean((Boolean) v);
                break;
            case ColumnType.STRING:
                writeString(out, (String) v);
                break;
            default:
                throw new IOException("unknown column type: " + type);
        }
    }

    private Object readValue(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case ColumnType.INT32: return in.readInt();
            case ColumnType.INT64:
            case ColumnType.DATETIME: return in.readLong();
            case ColumnType.FLOAT64: return in.readDouble();
            case ColumnType.BOOL: return in.readBoolean();
            case ColumnType.STRING: return readString(in);
            default:
                throw new IOException("unknown column type: " + type);
        }
    }

    private void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) throw new IOException("negative string length: " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
