package top.zhongnan.minidb.engine.table;

import top.zhongnan.minidb.storage.DataManager;
import top.zhongnan.minidb.sql_compiler.statement.Begin;
import top.zhongnan.minidb.sql_compiler.statement.Create;
import top.zhongnan.minidb.sql_compiler.statement.Delete;
import top.zhongnan.minidb.sql_compiler.statement.Drop;
import top.zhongnan.minidb.sql_compiler.statement.Insert;
import top.zhongnan.minidb.sql_compiler.statement.Select;
import top.zhongnan.minidb.sql_compiler.statement.Update;
import top.zhongnan.minidb.utils.Parser;
import top.zhongnan.minidb.engine.tx.VersionManager;
import top.zhongnan.minidb.engine.net.ResultSet;

public interface TableManager {
    BeginRes begin(Begin begin);
    byte[] commit(long xid) throws Exception;
    byte[] abort(long xid);

    byte[] show(long xid);
    byte[] create(long xid, Create create) throws Exception;

    byte[] insert(long xid, Insert insert) throws Exception;
    byte[] read(long xid, Select select) throws Exception;
    byte[] update(long xid, Update update) throws Exception;
    byte[] delete(long xid, Delete delete) throws Exception;

    /** 结构化结果集版本，供新协议使用。 */
    ResultSet readRS(long xid, Select select) throws Exception;
    /** SHOW TABLES 以结果集形式返回：单列 "table"。 */
    ResultSet showRS(long xid);
    /** DESC <table>：返回字段元信息（field / type / indexed）。 */
    ResultSet descRS(long xid, String tableName) throws Exception;
    /** DROP TABLE：返回受影响行数（成功为 1）。 */
    long drop(long xid, Drop drop) throws Exception;
    /** 当前已知表的数量（用于 SHOW STATS）。 */
    int tableCount();

    public static TableManager create(String path, VersionManager vm, DataManager dm) {
        Booter booter = Booter.create(path);
        booter.update(Parser.long2Byte(0));
        return new TableManagerImpl(vm, dm, booter);
    }

    public static TableManager open(String path, VersionManager vm, DataManager dm) {
        Booter booter = Booter.open(path);
        return new TableManagerImpl(vm, dm, booter);
    }
}
