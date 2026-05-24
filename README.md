# ZingDB

> 一个 Java 实现的教学型关系数据库。从零实现存储 / 事务 / MVCC / B+ 树索引 / SQL 解析与执行 / 网络协议 / 终端 UI 全栈，便于学习和调试。
>
> 致谢：早期版本借鉴并参考了开源项目 MyDB 的设计与实现，原始版权归 MyDB 原作者所有。本仓库在此基础上做了大量重构与扩展。
>
> 📖 [English README →](./README.en.md)

## 截图

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

## 核心特性

| 子系统 | 已实现 |
|---|---|
| **存储** | 分页文件、PageCache（LRU）、Redo/Undo 日志恢复 |
| **事务** | XID 管理、MVCC（Read Committed / Repeatable Read）、2PL 锁表、死锁检测 |
| **索引** | B+ Tree：`insert / range scan / delete`，按字段建索引 |
| **SQL** | 递归下降解析器：`CREATE / DROP / INSERT / SELECT / UPDATE / DELETE` |
| | `WHERE` 支持嵌套括号、`AND / OR / NOT`、`= != <> < <= > >=`、`IN`、`BETWEEN`、`LIKE`、`IS [NOT] NULL` |
| | `ORDER BY ASC/DESC`、`LIMIT n OFFSET m`、`SELECT COUNT(*)` |
| | `SHOW [TABLES] / DESC <table> / SHOW STATS` |
| **类型** | `int32 / int64 / float64 / bool / string / datetime` |
| **网络** | 二进制帧协议（magic + type + len + payload），结构化结果集，错误码 |
| **客户端** | JLine 3 REPL：多行输入、历史持久化、关键字 Tab 补全、Unicode 表格、配色、提示符状态机、psql 风格元命令 |
| **可观测** | SLF4J + Logback、慢查询日志（阈值可配）、`SHOW STATS` 全局指标 |

## 架构

```
┌───────────────────────────────────────────────────────────────┐
│                     CLIENT (JLine REPL)                       │
│   Banner / Prompter / TableRenderer / MetaCommand / Shell     │
└───────────────────────────┬───────────────────────────────────┘
                            │  二进制帧协议
                            │  [magic][type][len][payload]
┌───────────────────────────▼───────────────────────────────────┐
│                          SERVER                               │
│   ServerSocket + ThreadPool → HandleSocket(per conn)          │
│     ├─ Packager (Transporter + Encoder)                       │
│     └─ Executor → Parser (AST) → TableManager                 │
│                                  ├─ Planner (候选 uid 集合)   │
│                                  ├─ ExprEvaluator (二次过滤)  │
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
│                  │TransactionManager│ (XID 文件)              │
│                  └──────────────────┘                         │
│                                                               │
│   IndexManager: BPlusTree → Node → DataItem                   │
└───────────────────────────────────────────────────────────────┘
```

## 起步（macOS / Linux）

要求：JDK 1.8+、Maven 3.x。

### 1. 编译

```bash
mvn -q compile
```

### 2. 创建空数据库（仅首次）

```bash
mvn -q exec:java \
  -Dexec.mainClass="top.tankenqi.zingdb.backend.Launcher" \
  -Dexec.args="-create /tmp/zingdb/db"
```

会在 `/tmp/zingdb/` 下生成 `db.db / db.bt / db.log / db.xid` 四个文件。

### 3. 启动服务端

```bash
mvn -q exec:java \
  -Dexec.mainClass="top.tankenqi.zingdb.backend.Launcher" \
  -Dexec.args="-open /tmp/zingdb/db"
```

看到 `Server - ZingDB server listening on port 9999` 即成功。

可选参数：
- `-mem 128MB` 设置 PageCache 内存上限
- `-Dzingdb.slow.ms=100` 调慢查询阈值（默认 200ms）

### 4. 启动客户端

新开一个终端：

```bash
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher"
```

非交互用法：

```bash
# 执行单条 SQL
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher" \
  -Dexec.args="-e 'select * from users'"

# 执行脚本文件
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher" \
  -Dexec.args="-f schema.sql"

# 远程连接 + 关闭颜色
mvn -q exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher" \
  -Dexec.args="--host 10.0.0.5 --port 9999 --no-color"
```

## 客户端使用

启动后会看到一个 banner 和青色提示符 `zingdb ›`。

**提示符状态**
- `zingdb ›` 青色：空闲
- `zingdb* ›` 黄色：当前在事务中（begin 之后）
- `zingdb! ›` 红色：上一条出错（下一条成功后自动恢复）

**元命令（psql 风格）**

| 命令 | 作用 |
|---|---|
| `\h` / `\help` / `\?` / `help` | 显示帮助 |
| `\q` / `\quit` / `\exit` / `quit` / `exit` | 退出 |
| `\dt` | 列出所有表（等同 `show`） |
| `\d <table>` | 查看表结构（等同 `desc`） |
| `\stats` | 服务端运行指标（连接数 / 查询数 / 慢查询数 / uptime） |
| `\timing` | 切换是否显示耗时 |
| `\json` | 切换 JSON 输出（占位） |
| `\!` | 重连服务器（占位） |

**多行输入**：以 `;` 结尾时提交，中间会看到续行符 `       …`。

**历史与补全**：上下方向键翻历史（持久化到 `~/.zingdb_history`），Tab 补全 SQL 关键字与元命令。

## SQL 速查

### DDL

```sql
-- 建表（最后括号内列出索引字段）
create table users
    id int32, name string, age int32, score float64, born datetime, active bool,
    (index id age);

drop table users;
desc users;
show tables;
```

支持类型：`int32` `int64` `float64` `bool` `string` `datetime`。

### DML

```sql
insert into users values (1, 'alice', 23, 95.5, '2002-01-15', true);
-- 也接受不带括号的旧写法
insert into users values 2 'bob' 30 88.0 '1995-04-20 09:00:00' false;

select * from users;
select id, name from users where age > 18 order by age desc limit 5 offset 0;
select count(*) from users where active = true;

update users set age = 24 where name = 'alice';
delete from users where id = 1;
```

### WHERE 表达式

```sql
-- 比较
where id = 1
where id != 1            -- 同义 <>
where age >= 18

-- 逻辑 + 括号
where (id = 1 or id = 3) and age > 5
where not active

-- 集合 / 范围 / 模糊
where id in (1, 2, 3)
where id not in (4, 5)
where age between 18 and 30
where name like 'al%'    -- % 任意串，_ 任意单字符
where name not like 'a_'

-- 空值（注意：当前数据持久化不支持 NULL 值，IS NULL 主要用于 evaluator 语义）
where x is null
where x is not null
```

### 事务

```sql
begin;                                     -- 默认 read committed
begin isolation level repeatable read;
commit;
abort;
```

### 注释 & 大小写

```sql
-- 单行注释
SELECT * FROM users;       /* 块注释 */
```

关键字大小写不敏感；标识符大小写敏感。

## 协议（v2）

帧格式：

```
[magic 2B = 0x5A 0x44][type 1B][len 4B BE][payload ...]
```

`type` 取值：

| 值 | 名字 | payload |
|---|---|---|
| 0x01 | REQUEST | `[sqlLen:4][sql:utf8]` |
| 0x02 | OK | `[rowsAffected:8][elapsedNanos:8][msgLen:4][msg:utf8]` |
| 0x03 | RESULT_SET | 见下 |
| 0x04 | ERROR | `[codeLen:4][code:utf8][msgLen:4][msg:utf8]` |

RESULT_SET payload：

```
[colCount:4]
{ [nameLen:4][name:utf8][type:1] } * colCount
[rowCount:4]
{
    nullBitmap (ceil(colCount/8) bytes)
    { value 按列类型编码 } * (非NULL列)
} * rowCount
[noteLen:4][note:utf8]
[elapsedNanos:8]
```

值编码：`INT32=4B`、`INT64=8B`、`FLOAT64=8B(IEEE754)`、`BOOL=1B`、`DATETIME=8B(ms)`、`STRING=[len:4][bytes:utf8]`。

## 错误码

错误以 `[CODE] message` 形式返回，CODE 按子系统分段：

| 段 | 子系统 | 例 |
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

## 可观测

`show stats` / `\stats` 返回如下指标：

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

慢查询日志（默认 ≥ 200 ms）会写到 `WARN` 级别：

```
01:23:45.678 WARN  slow-query - slow query (312 ms) :: select * from big where ...
```

调整阈值：启动服务端时加 `-Dzingdb.slow.ms=100`。

## 项目结构

```
src/main/java/top/tankenqi/zingdb/
├─ backend/
│  ├─ Launcher.java                      入口（create / open）
│  ├─ common/                            错误码 / 异常
│  ├─ dm/                                数据管理（页 / 缓存 / 日志 / DataItem）
│  ├─ tm/                                事务 ID 管理
│  ├─ vm/                                MVCC + 锁表 + 死锁检测
│  ├─ im/                                B+ Tree 索引
│  ├─ tbm/                               表 / 字段 / Planner / Evaluator
│  ├─ parser/                            Tokenizer / Parser / AST
│  └─ server/                            Server / Executor / Metrics / SlowQueryLogger
├─ client/
│  ├─ Launcher.java                      客户端入口（CLI 参数）
│  ├─ Client.java / RoundTripper.java    传输封装
│  ├─ Shell.java                         JLine REPL
│  └─ ui/
│     ├─ Ansi.java                       ANSI 转义工具
│     ├─ Theme.java                      配色 + Unicode 字符
│     ├─ TerminalCaps.java               终端能力探测
│     ├─ TableRenderer.java              结果集 → Unicode 表格
│     ├─ Banner.java                     启动卡片
│     ├─ Prompter.java                   提示符状态机
│     ├─ MetaCommand.java                \ 命令解析
│     └─ HelpPrinter.java                \h 帮助
├─ transport/                            协议层（Encoder / Transporter / Package / ResultSet）
└─ common/
   ├─ Error.java                         预定义错误对象
   └─ ZingDBException.java               带错误码的异常基类

src/test/java/...                        66 个单元 + 端到端测试
```

## 开发

```bash
# 跑所有测试
mvn test

# 关键测试文件
src/test/java/top/tankenqi/zingdb/backend/parser/ParserV2Test.java    # AST 解析
src/test/java/top/tankenqi/zingdb/backend/server/EndToEndSqlTest.java # 端到端 SQL
src/test/java/top/tankenqi/zingdb/backend/server/StatsTest.java       # SHOW STATS
src/test/java/top/tankenqi/zingdb/backend/server/SlowQueryLoggerTest.java
src/test/java/top/tankenqi/zingdb/transport/PackagerTest.java         # 协议
src/test/java/top/tankenqi/zingdb/client/ui/TableRendererTest.java    # 终端渲染
```

## 已知限制（教学型 DB 取舍）

- entry 编码不含 null bitmap → 数据列暂不能持久化 SQL NULL（`IS NULL` 谓词依然可用于查询）。
- `DROP TABLE` 用墓碑标记实现（booter 维护被删表名单），磁盘上的表 entry 与 B+ Tree 数据不回收。
- `float64` 通过 IEEE 754 位模式做索引排序，含负数的范围查询排序不严格正确（等值匹配 OK）。
- 仅支持单表查询，无 `JOIN` / `GROUP BY` / 聚合（除 `COUNT(*)`）。
- 协议未做 TLS / 鉴权 / 限流。

## 许可证

[Apache License 2.0](LICENSE)
