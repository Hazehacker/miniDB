package top.tankenqi.zingdb.backend.tbm;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.tankenqi.zingdb.backend.dm.DataManager;
import top.tankenqi.zingdb.backend.parser.statement.Begin;
import top.tankenqi.zingdb.backend.parser.statement.Create;
import top.tankenqi.zingdb.backend.parser.statement.Delete;
import top.tankenqi.zingdb.backend.parser.statement.Drop;
import top.tankenqi.zingdb.backend.parser.statement.Insert;
import top.tankenqi.zingdb.backend.parser.statement.Select;
import top.tankenqi.zingdb.backend.parser.statement.Update;
import top.tankenqi.zingdb.backend.utils.Parser;
import top.tankenqi.zingdb.backend.vm.VersionManager;
import top.tankenqi.zingdb.common.Error;
import top.tankenqi.zingdb.transport.ColumnType;
import top.tankenqi.zingdb.transport.ResultSet;

public class TableManagerImpl implements TableManager {
    VersionManager vm;
    DataManager dm;
    private Booter booter;
    private Map<String, Table> tableCache;
    private Map<Long, List<Table>> xidTableCache;
    private Set<String> droppedNames;
    private Lock lock;

    TableManagerImpl(VersionManager vm, DataManager dm, Booter booter) {
        this.vm = vm;
        this.dm = dm;
        this.booter = booter;
        this.tableCache = new HashMap<>();
        this.xidTableCache = new HashMap<>();
        this.droppedNames = new HashSet<>();
        this.lock = new ReentrantLock();
        loadBooter();
        loadTables();
    }

    /**
     * Booter 二进制布局（兼容旧版本：仅 8 字节时视为无 dropped 名单）：
     *   [firstTableUid:8B] [droppedCount:4B] {[nameLen:4B][nameBytes]} * droppedCount
     */
    private long currentFirstTableUid;

    private void loadBooter() {
        byte[] raw = booter.load();
        if (raw == null || raw.length < 8) {
            currentFirstTableUid = 0;
            return;
        }
        currentFirstTableUid = Parser.parseLong(Arrays.copyOfRange(raw, 0, 8));
        if (raw.length == 8) return;
        try {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(raw, 8, raw.length - 8));
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int len = in.readInt();
                byte[] b = new byte[len];
                in.readFully(b);
                droppedNames.add(new String(b, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // booter 格式不完整：保守起见忽略 dropped 名单（旧数据无 dropped，问题不大）
        }
    }

    private void persistBooter() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.write(Parser.long2Byte(currentFirstTableUid));
            out.writeInt(droppedNames.size());
            for (String n : droppedNames) {
                byte[] b = n.getBytes(StandardCharsets.UTF_8);
                out.writeInt(b.length);
                out.write(b);
            }
            booter.update(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("persist booter failed", e);
        }
    }

    private void loadTables() {
        long uid = currentFirstTableUid;
        while (uid != 0) {
            Table tb = Table.loadTable(this, uid);
            uid = tb.nextUid;
            if (!droppedNames.contains(tb.name)) {
                tableCache.put(tb.name, tb);
            }
        }
    }

    private long firstTableUid() {
        return currentFirstTableUid;
    }

    private void updateFirstTableUid(long uid) {
        currentFirstTableUid = uid;
        persistBooter();
    }

    @Override
    public BeginRes begin(Begin begin) {
        BeginRes res = new BeginRes();
        int level = begin.isRepeatableRead ? 1 : 0;
        res.xid = vm.begin(level);
        res.result = "begin".getBytes();
        return res;
    }

    @Override
    public byte[] commit(long xid) throws Exception {
        vm.commit(xid);
        return "commit".getBytes();
    }

    @Override
    public byte[] abort(long xid) {
        vm.abort(xid);
        return "abort".getBytes();
    }

    @Override
    public byte[] show(long xid) {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (Table tb : tableCache.values()) {
                sb.append(tb.toString()).append("\n");
            }
            List<Table> t = xidTableCache.get(xid);
            if (t == null) return "\n".getBytes();
            for (Table tb : t) sb.append(tb.toString()).append("\n");
            return sb.toString().getBytes();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] create(long xid, Create create) throws Exception {
        lock.lock();
        try {
            if (tableCache.containsKey(create.tableName)) {
                throw Error.DuplicatedTableException;
            }
            // 如果重新创建一个之前被 drop 的表，先把它从 dropped 名单移除
            boolean wasDropped = droppedNames.remove(create.tableName);
            Table table = Table.createTable(this, firstTableUid(), xid, create);
            updateFirstTableUid(table.uid); // 已 persist booter
            tableCache.put(create.tableName, table);
            xidTableCache.computeIfAbsent(xid, k -> new ArrayList<>()).add(table);
            if (wasDropped) persistBooter();   // 名单可能改了，确保落盘
            return ("create " + create.tableName).getBytes();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] insert(long xid, Insert insert) throws Exception {
        Table table = lookup(insert.tableName);
        table.insert(xid, insert);
        return "insert".getBytes();
    }

    @Override
    public byte[] read(long xid, Select read) throws Exception {
        Table table = lookup(read.tableName);
        return table.read(xid, read).getBytes();
    }

    @Override
    public byte[] update(long xid, Update update) throws Exception {
        Table table = lookup(update.tableName);
        int count = table.update(xid, update);
        return ("update " + count).getBytes();
    }

    @Override
    public byte[] delete(long xid, Delete delete) throws Exception {
        Table table = lookup(delete.tableName);
        int count = table.delete(xid, delete);
        return ("delete " + count).getBytes();
    }

    private Table lookup(String name) {
        lock.lock();
        try {
            Table table = tableCache.get(name);
            if (table == null) throw (RuntimeException) Error.TableNotFoundException;
            return table;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ResultSet readRS(long xid, Select select) throws Exception {
        Table table = lookup(select.tableName);
        return table.readForResultSet(xid, select);
    }

    @Override
    public ResultSet showRS(long xid) {
        lock.lock();
        try {
            ResultSet rs = new ResultSet(
                new String[]{"table"},
                new byte[]{ColumnType.STRING});
            for (Table tb : tableCache.values()) {
                rs.addRow(new Object[]{tb.toString()});
            }
            List<Table> t = xidTableCache.get(xid);
            if (t != null) {
                for (Table tb : t) rs.addRow(new Object[]{tb.toString()});
            }
            return rs;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ResultSet descRS(long xid, String tableName) throws Exception {
        Table table = lookup(tableName);
        ResultSet rs = new ResultSet(
            new String[]{"field", "type", "indexed"},
            new byte[]{ColumnType.STRING, ColumnType.STRING, ColumnType.BOOL});
        for (Field f : table.fields) {
            rs.addRow(new Object[]{f.getName(), f.getType(), f.isIndexed()});
        }
        return rs;
    }

    @Override
    public int tableCount() {
        lock.lock();
        try { return tableCache.size(); }
        finally { lock.unlock(); }
    }

    /**
     * DROP TABLE 的实现策略（教学型，可接受空间不回收）：
     *   - 从内存 tableCache 移除目标表
     *   - 把表名加入 booter 的 dropped 名单并持久化
     *   - 表自身的链表 entry 与 B+ Tree 数据**保留**在磁盘上，启动时通过 dropped 名单跳过；
     *     这样无需修改链表前驱（VM 不支持原地 update，重建链表会让所有表 uid 变化，
     *     增加事务可见性 / xidTableCache 等问题面），权衡后保留空间换简单与安全。
     */
    @Override
    public long drop(long xid, Drop drop) throws Exception {
        lock.lock();
        try {
            Table table = tableCache.get(drop.tableName);
            if (table == null) throw (RuntimeException) Error.TableNotFoundException;
            tableCache.remove(drop.tableName);
            droppedNames.add(drop.tableName);
            persistBooter();
            // 同时清理 xidTableCache 中该事务创建的同名表（如果有）
            List<Table> tlist = xidTableCache.get(xid);
            if (tlist != null) tlist.removeIf(t -> t.name.equals(drop.tableName));
            return 1L;
        } finally {
            lock.unlock();
        }
    }
}
