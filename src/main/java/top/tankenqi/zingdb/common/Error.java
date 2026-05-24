package top.tankenqi.zingdb.common;

/**
 * 预定义的错误对象。
 *
 * 为了不影响下游使用，仍保留 public static final Exception 字段访问方式，
 * 但底层类型升级为 ZingDBException，自动携带错误码，便于客户端按错误码做差异化渲染。
 */
public class Error {

    // ===== common (CM) =====
    public static final Exception CacheFullException = new ZingDBException("CM-0001", "Cache is full!");
    public static final Exception FileExistsException = new ZingDBException("CM-0002", "File already exists!");
    public static final Exception FileNotExistsException = new ZingDBException("CM-0003", "File does not exists!");
    public static final Exception FileCannotRWException = new ZingDBException("CM-0004", "File cannot read or write!");

    // ===== dm (DM) =====
    public static final Exception BadLogFileException = new ZingDBException("DM-0001", "Bad log file!");
    public static final Exception MemTooSmallException = new ZingDBException("DM-0002", "Memory too small!");
    public static final Exception DataTooLargeException = new ZingDBException("DM-0003", "Data too large!");
    public static final Exception DatabaseBusyException = new ZingDBException("DM-0004", "Database is busy!");

    // ===== tm (TM) =====
    public static final Exception BadXIDFileException = new ZingDBException("TM-0001", "Bad XID file!");

    // ===== vm (VM) =====
    public static final Exception DeadlockException = new ZingDBException("VM-0001", "Deadlock!");
    public static final Exception ConcurrentUpdateException = new ZingDBException("VM-0002", "Concurrent update issue!");
    public static final Exception NullEntryException = new ZingDBException("VM-0003", "Null entry!");

    // ===== tbm (TB) =====
    public static final Exception InvalidFieldException = new ZingDBException("TB-0001", "Invalid field type!");
    public static final Exception FieldNotFoundException = new ZingDBException("TB-0002", "Field not found!");
    public static final Exception FieldNotIndexedException = new ZingDBException("TB-0003", "Field not indexed!");
    public static final Exception InvalidLogOpException = new ZingDBException("TB-0004", "Invalid logic operation!");
    public static final Exception InvalidValuesException = new ZingDBException("TB-0005", "Invalid values!");
    public static final Exception DuplicatedTableException = new ZingDBException("TB-0006", "Duplicated table!");
    public static final Exception TableNotFoundException = new ZingDBException("TB-0007", "Table not found!");

    // ===== parser (PR) =====
    public static final Exception InvalidCommandException = new ZingDBException("PR-0001", "Invalid command!");
    public static final Exception TableNoIndexException = new ZingDBException("PR-0002", "Table has no index!");

    // ===== transport (TP) =====
    public static final Exception InvalidPkgDataException = new ZingDBException("TP-0001", "Invalid package data!");

    // ===== server (SV) =====
    public static final Exception NestedTransactionException = new ZingDBException("SV-0001", "Nested transaction not supported!");
    public static final Exception NoTransactionException = new ZingDBException("SV-0002", "Not in transaction!");

    // ===== launcher (LC) =====
    public static final Exception InvalidMemException = new ZingDBException("LC-0001", "Invalid memory!");
}
