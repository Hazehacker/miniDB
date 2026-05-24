# ZingDB

> A teaching-grade relational database written from scratch in Java. Implements the full stack from storage, transactions, MVCC, B+ tree indexing, SQL parsing and execution, wire protocol, all the way to a colorful terminal UI — built to be read, debugged, and extended.
>
> Acknowledgements: early versions of ZingDB borrowed design ideas from the open-source project MyDB. Original copyright remains with the MyDB authors. This repository has since gone through extensive refactoring and feature work.
>
> 📖 [中文 README →](./README.md)

## Screenshot

```
zingdb › select id, name, age from users where age > 18 order by age desc;

┌────┬─────────┬─────┐
│ id │ name    │ age │
├────┼─────────┼─────┤
│  4 │ diana   │  45 │
│  2 │ bob     │  30 │
│  1 │ alice   │  23 │
└────┴─────────┴─────┘
3 rows  ·  2.05 ms
```

## Features

| Subsystem | What's there |
|---|---|
| **Storage** | Paged file storage, LRU PageCache, redo/undo log-based recovery |
| **Transactions** | XID manager, MVCC (Read Committed / Repeatable Read), 2PL lock table, deadlock detection |
| **Index** | B+ tree with `insert / range scan / delete`, per-column indexes |
| **SQL** | Recursive-descent parser: `CREATE / DROP / INSERT / SELECT / UPDATE / DELETE` |
| | `WHERE` supports nested parens, `AND / OR / NOT`, `= != <> < <= > >=`, `IN`, `BETWEEN`, `LIKE`, `IS [NOT] NULL` |
| | `ORDER BY ASC/DESC`, `LIMIT n OFFSET m`, `SELECT COUNT(*)` |
| | `SHOW [TABLES] / DESC <table> / SHOW STATS` |
| **Types** | `int32 / int64 / float64 / bool / string / datetime` |
| **Wire** | Binary framing protocol (magic + type + len + payload), structured result sets, typed error codes |
| **Client** | JLine 3 REPL: multi-line input, persistent history, keyword Tab completion, Unicode tables, color, prompt state machine, psql-style meta commands |
| **Observability** | SLF4J + Logback, slow-query log (threshold configurable), `SHOW STATS` runtime metrics |

## Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                     CLIENT (JLine REPL)                       │
│   Banner / Prompter / TableRenderer / MetaCommand / Shell     │
└───────────────────────────┬───────────────────────────────────┘
                            │  binary frame protocol
                            │  [magic][type][len][payload]
┌───────────────────────────▼───────────────────────────────────┐
│                          SERVER                               │
│   ServerSocket + ThreadPool → HandleSocket(per conn)          │
│     ├─ Packager (Transporter + Encoder)                       │
│     └─ Executor → Parser (AST) → TableManager                 │
│                                  ├─ Planner (uid candidate set)│
│                                  ├─ ExprEvaluator (post-filter)│
│                                  └─ Table → Field             │
│                       ┌────────────────────┴──────────┐       │
│   ┌─── VersionManager ───┐    ┌────── DataManager ─────────┐  │
│   │  Transaction         │    │  Logger (Redo/Undo)        │  │
│   │  Visibility (MVCC)   │    │  PageCache (LRU)           │  │
│   │  LockTable (2PL+DL)  │    │  DataItem / Page           │  │
│   └─────────┬────────────┘    └──────────┬─────────────────┘  │
│             │                            │                    │
│             └────────┐         ┌─────────┘                    │
│                  ┌───▼─────────▼───┐                          │
│                  │TransactionManager│ (XID file)              │
│                  └──────────────────┘                         │
│                                                               │
│   IndexManager: BPlusTree → Node → DataItem                   │
└───────────────────────────────────────────────────────────────┘
```

## Getting Started (macOS / Linux)

Requirements: JDK 1.8+, Maven 3.x.

### 1. Build

```bash
mvn -q compile
```

### 2. Create an empty database (one-off)

```bash
mvn -q exec:java \
  -Dexec.mainClass="top.tankenqi.zingdb.backend.Launcher" \
  -Dexec.args="-create /tmp/zingdb/db"
```

This creates `db.db / db.bt / db.log / db.xid` under `/tmp/zingdb/`.

### 3. Start the server

```bash
mvn -q exec:java \
  -Dexec.mainClass="top.tankenqi.zingdb.backend.Launcher" \
  -Dexec.args="-open /tmp/zingdb/db"
```

You should see `Server - ZingDB server listening on port 9999`.

Useful flags:
- `-mem 128MB` set PageCache memory cap
- `-Dzingdb.slow.ms=100` slow-query threshold (default 200 ms)

### 4. Start the client

In a new terminal:

```bash
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher"
```

Non-interactive usage:

```bash
# one-shot SQL
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher" \
  -Dexec.args="-e 'select * from users'"

# script file
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher" \
  -Dexec.args="-f schema.sql"

# remote + no color
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher" \
  -Dexec.args="--host 10.0.0.5 --port 9999 --no-color"
```

## Using the Client

After launch you'll see a banner and a cyan prompt `zingdb ›`.

**Prompt states**
- `zingdb ›` cyan: idle
- `zingdb* ›` yellow: inside a transaction (after `begin`)
- `zingdb! ›` red: the last statement errored (clears on next success)

**Meta commands (psql-style)**

| Command | Effect |
|---|---|
| `\h` / `\help` / `\?` / `help` | show help |
| `\q` / `\quit` / `\exit` / `quit` / `exit` | leave the client |
| `\dt` | list all tables (alias of `show`) |
| `\d <table>` | describe a table (alias of `desc <table>`) |
| `\stats` | server runtime metrics (connections / queries / slow queries / uptime) |
| `\timing` | toggle showing execution time |
| `\json` | toggle JSON output (placeholder) |
| `\!` | reconnect to the server (placeholder) |

**Multi-line input**: commit on a trailing `;`. While buffering you'll see the continuation prompt `       …`.

**History & completion**: arrow keys cycle history (persisted to `~/.zingdb_history`); Tab completes SQL keywords and meta commands.

## SQL Cheat Sheet

### DDL

```sql
-- Create table (indexed columns go in the trailing parentheses)
create table users
    id int32, name string, age int32, score float64, born datetime, active bool,
    (index id age);

drop table users;
desc users;
show tables;
```

Supported types: `int32` `int64` `float64` `bool` `string` `datetime`.

### DML

```sql
insert into users values (1, 'alice', 23, 95.5, '2002-01-15', true);
-- legacy parenthesis-less syntax also works
insert into users values 2 'bob' 30 88.0 '1995-04-20 09:00:00' false;

select * from users;
select id, name from users where age > 18 order by age desc limit 5 offset 0;
select count(*) from users where active = true;

update users set age = 24 where name = 'alice';
delete from users where id = 1;
```

### WHERE expressions

```sql
-- comparison
where id = 1
where id != 1            -- same as <>
where age >= 18

-- logical + parens
where (id = 1 or id = 3) and age > 5
where not active

-- set / range / pattern
where id in (1, 2, 3)
where id not in (4, 5)
where age between 18 and 30
where name like 'al%'    -- % any string, _ any single char
where name not like 'a_'

-- null (note: persistent NULL values are not yet supported; IS NULL is mostly
-- useful at the evaluator level)
where x is null
where x is not null
```

### Transactions

```sql
begin;                                     -- defaults to read committed
begin isolation level repeatable read;
commit;
abort;
```

### Comments & case sensitivity

```sql
-- single-line comment
SELECT * FROM users;       /* block comment */
```

Keywords are case-insensitive; identifiers are case-sensitive.

## Wire Protocol (v2)

Frame format:

```
[magic 2B = 0x5A 0x44][type 1B][len 4B BE][payload ...]
```

`type` values:

| Value | Name | Payload |
|---|---|---|
| 0x01 | REQUEST | `[sqlLen:4][sql:utf8]` |
| 0x02 | OK | `[rowsAffected:8][elapsedNanos:8][msgLen:4][msg:utf8]` |
| 0x03 | RESULT_SET | see below |
| 0x04 | ERROR | `[codeLen:4][code:utf8][msgLen:4][msg:utf8]` |

RESULT_SET payload:

```
[colCount:4]
{ [nameLen:4][name:utf8][type:1] } * colCount
[rowCount:4]
{
    nullBitmap (ceil(colCount/8) bytes)
    { value encoded according to column type } * (non-NULL columns)
} * rowCount
[noteLen:4][note:utf8]
[elapsedNanos:8]
```

Value encoding: `INT32=4B`, `INT64=8B`, `FLOAT64=8B (IEEE 754)`, `BOOL=1B`, `DATETIME=8B (ms)`, `STRING=[len:4][bytes:utf8]`.

## Error Codes

Errors come back as `[CODE] message`. Codes are namespaced by subsystem:

| Prefix | Subsystem | Example |
|---|---|---|
| `CM-xxxx` | common (cache / file) | `CM-0001 Cache is full!` |
| `DM-xxxx` | data manager | `DM-0003 Data too large!` |
| `TM-xxxx` | transaction manager | `TM-0001 Bad XID file!` |
| `VM-xxxx` | version manager (MVCC) | `VM-0001 Deadlock!` |
| `TB-xxxx` | table manager | `TB-0007 Table not found!` |
| `PR-xxxx` | parser | `PR-0001 Invalid command!` |
| `TP-xxxx` | transport | `TP-0001 Invalid package data!` |
| `SV-xxxx` | server | `SV-0001 Nested transaction not supported!` |
| `LC-xxxx` | launcher | `LC-0001 Invalid memory!` |

## Observability

`show stats` / `\stats` returns:

```
zingdb › \stats
┌────────────────────┬──────────┐
│ metric             │ value    │
├────────────────────┼──────────┤
│ uptime             │ 12s      │
│ connections.active │ 1        │
│ connections.total  │ 3        │
│ queries.total      │ 27       │
│ queries.errors     │ 2        │
│ queries.slow       │ 0        │
│ queries.avg_ms     │ 0.943    │
│ slow_threshold_ms  │ 200      │
│ tables             │ 1        │
└────────────────────┴──────────┘
```

Slow-query log (default ≥ 200 ms) is logged at `WARN`:

```
01:23:45.678 WARN  slow-query - slow query (312 ms) :: select * from big where ...
```

Override the threshold with `-Dzingdb.slow.ms=100` when starting the server.

## Project Layout

```
src/main/java/top/tankenqi/zingdb/
├─ backend/
│  ├─ Launcher.java                       entry point (create / open)
│  ├─ common/                             error codes / exceptions
│  ├─ dm/                                 data management (page / cache / log / DataItem)
│  ├─ tm/                                 transaction ID manager
│  ├─ vm/                                 MVCC + lock table + deadlock detection
│  ├─ im/                                 B+ tree index
│  ├─ tbm/                                table / field / Planner / Evaluator
│  ├─ parser/                             Tokenizer / Parser / AST
│  └─ server/                             Server / Executor / Metrics / SlowQueryLogger
├─ client/
│  ├─ Launcher.java                       client entry (CLI parsing)
│  ├─ Client.java / RoundTripper.java     transport helpers
│  ├─ Shell.java                          JLine REPL
│  └─ ui/
│     ├─ Ansi.java                        ANSI escape helpers
│     ├─ Theme.java                       colors + Unicode glyphs
│     ├─ TerminalCaps.java                terminal capability detection
│     ├─ TableRenderer.java               ResultSet → Unicode table
│     ├─ Banner.java                      startup card
│     ├─ Prompter.java                    prompt state machine
│     ├─ MetaCommand.java                 \-command parser
│     └─ HelpPrinter.java                 \h help screen
├─ transport/                             protocol layer (Encoder / Transporter / Package / ResultSet)
└─ common/
   ├─ Error.java                          pre-baked error instances
   └─ ZingDBException.java                exception base type with error codes

src/test/java/...                         66 unit + end-to-end tests
```

## Development

```bash
# run all tests
mvn test

# key test files
src/test/java/top/tankenqi/zingdb/backend/parser/ParserV2Test.java     # AST parser
src/test/java/top/tankenqi/zingdb/backend/server/EndToEndSqlTest.java  # end-to-end SQL
src/test/java/top/tankenqi/zingdb/backend/server/StatsTest.java        # SHOW STATS
src/test/java/top/tankenqi/zingdb/backend/server/SlowQueryLoggerTest.java
src/test/java/top/tankenqi/zingdb/transport/PackagerTest.java          # wire protocol
src/test/java/top/tankenqi/zingdb/client/ui/TableRendererTest.java     # terminal rendering
```

## Known Limitations (teaching-grade tradeoffs)

- Entry encoding has no null bitmap → SQL NULL cannot be persisted in data columns (`IS NULL` still works at the evaluator level).
- `DROP TABLE` is implemented via a tombstone list in `booter`; on-disk table entries and B+ tree pages are not reclaimed.
- `float64` index ordering uses the IEEE 754 bit pattern; range scans involving negative numbers are not strictly correctly ordered (equality matches are fine).
- Single-table queries only — no `JOIN`, no `GROUP BY`, no aggregates other than `COUNT(*)`.
- No TLS, authentication, or rate limiting on the wire protocol.

## License

[Apache License 2.0](LICENSE)
