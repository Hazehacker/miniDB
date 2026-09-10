# 理解 miniDB —— 架构与源码导览

README 回答「这个数据库有什么功能」，这份文档回答「它是怎么运转的、为什么这么写、坑在哪里」。

适用读者：第一次读这套代码的人、要在此基础上继续做实训任务的人、评审时想快速定位实现位置的人。阅读前置：会 Java，知道 B+ 树、WAL、MVCC 大概是干什么的即可，不要求实现过数据库。

> 文中路径均相对仓库根目录。行号会随代码演进而漂移，路径不会。

---

## 1. 五分钟跑起来

以下命令用于 macOS/Linux 的 Bash；Windows 使用后面的启动脚本。

```bash
mkdir -p data
mvn -q dependency:build-classpath -Dmdep.outputFile=data/cp.txt
mvn -q clean compile                 # 必须 clean：target/ 里可能残留旧包的 class

# 首次：建一个空库（会生成 db.db / db.bt / db.log / db.xid）
java -cp "target/classes:$(cat data/cp.txt)" top.zhongnan.minidb.engine.Launcher -create data/db

# 起服务端（阻塞在 9999 端口）
java -cp "target/classes:$(cat data/cp.txt)" top.zhongnan.minidb.engine.Launcher -open data/db

# 另开一个终端：客户端
java -cp "target/classes:$(cat data/cp.txt)" top.zhongnan.minidb.cli.Launcher
```

Windows 首次使用前，先运行 `mvn -q clean compile dependency:build-classpath -Dmdep.outputFile=data/cp.txt`，并在 PowerShell 用 `java -cp "target/classes;$(Get-Content data/cp.txt)" top.zhongnan.minidb.engine.Launcher -create data/db` 建库。之后可运行启动脚本。

Windows 上仓库已备好 `data/start-server.cmd` 与 `data/start-client.cmd`，它们做了两件必要的事：`chcp 65001` 和 `-Dstdout.encoding=UTF-8`。**没有这两步，中文表头和内容会乱码**——JDK 18+ 会按控制台编码推断 `stdout.encoding`，中文 Windows 默认 GBK。

第一组 SQL（注意建表语法，见 §7.1）：

```sql
create table users id int32, name string, age int32, (index id age);
insert into users values (1, 'alice', 23);
insert into users values (2, 'bob', 30);
select * from users where age > 18 order by age desc;
-- 回滚关键字是 abort；每条语句分别提交给客户端。
begin;
insert into users values (3, 'carol', 41);
abort;
show tables;
```

非交互模式适合脚本化和冒烟验证：

```bash
java -cp "target/classes:$(cat data/cp.txt)" top.zhongnan.minidb.cli.Launcher --no-color -e "select * from users"
java -cp "target/classes:$(cat data/cp.txt)" top.zhongnan.minidb.cli.Launcher -f schema.sql
```

> 交互模式使用 JLine 的 `system(true)` 终端，**必须有真实 TTY**。在管道或某些 IDE 控制台里跑会失败——用 `-e` / `-f` 代替。

---

## 2. 一次查询的完整旅程

以 `select id, name from users where age > 18 order by age desc limit 5;` 为例，从敲回车到看到表格：

```
cli/Shell.java                读一行 → 去分号 → client.execute(sql)
  └─ cli/Client.java          Package.request(sql)
      └─ cli/RoundTripper     packager.send(pkg)  ← 阻塞，一问一答，无流水线
          ├─ engine/net/Encoder       编码成 [sqlLen:4][sql:utf8]
          └─ engine/net/Transporter   加帧 [Z D][type:1][len:4]  →  socket

── TCP ──

engine/Server.java            accept() → 线程池 → new HandleSocket(socket)
  └─ HandleSocket             每连接一个 Executor，循环 receive → execute → send
      └─ engine/Executor.java 计时 → Parser.Parse → 分发
          └─ sql_compiler/Parser.Parse(byte[])   递归下降 → Select 对象
              └─ engine/table/TableManagerImpl.readRS
                  └─ engine/table/Table.readForResultSet
                      ├─ resolveCandidates:
                      │    ExprEvaluator.validate(expr)   先校验字段/字面量（空表也要报错）
                      │    Planner.plan(expr)             → 候选 uid 集合（超集！）
                      ├─ 逐 uid: VersionManager.read(xid, uid)   ← MVCC 可见性判定，null 即跳过
                      ├─ Field.parserValue → Map<列名,值>       行解码
                      ├─ ExprEvaluator.eval(expr, entry)       ← 二次过滤，保证正确性
                      ├─ ORDER BY（内存排序）→ LIMIT/OFFSET
                      └─ 投影装配 ResultSet
                  └─ engine/net/Package.resultSet(rs)
                      └─ Encoder 编码 → ResultSet 帧 → socket

cli/ui/TableRenderer.render(pkg) → Unicode 表格 + "N rows · 2.05 ms"
```

三个要点，后面会反复用到：

1. **索引只做加速，不负责正确性。** `Planner` 返回的是**候选超集**，最终每行都要过 `ExprEvaluator`。所以「索引字段范围查询不准」不会导致查错，只会导致查慢。
2. **可见性判定发生在读的时候。** `VersionManager.read` 返回 `null` 表示「这行对你不可见」，调用方直接 `continue`。删除也不是物理删除。
3. **客户端完全无状态。** 事务的「在不在事务里」由服务端 `Executor.xid` 决定，客户端的提示符颜色只是对返回消息文本的启发式猜测（`Shell.inferInTx` 读 `"begin"` / `"commit"` / `"abort"` 字符串）。

---

## 3. 五模块与依赖方向

```
top.zhongnan.minidb/
├─ cli/           命令行接口     Shell / Launcher / Client / ui/
├─ engine/        引擎           Server / Executor / table/ / tx/ / net/
│   ├─ table/                    表结构、字段、行目录
│   ├─ tx/                       XID 管理 + MVCC + 锁表
│   └─ net/                      协议编解码
├─ sql_compiler/  SQL 编译器     Tokenizer / Parser / Planner / ExprEvaluator / statement/
├─ storage/       存储           DataManager / page/ / buffer/ / dataItem/ / logger/ / index/
└─ utils/        工具与常量      Error / Parser / AbstractCache / SubArray / Panic
```

依赖方向（箭头 = 依赖）：

```
cli ──▶ engine.net ──(线缆)──▶ engine ──▶ sql_compiler ──▶ engine.table
                                            │                    │
                                            ▼                    ▼
                                         storage ◀──────── engine.tx
                                            │
                                            ▼
                                          utils
```

两点需要知道：

- **`sql_compiler ⇄ engine.table` 是一个包循环。** `Planner` 需要 `engine.table.Field` 才能查索引，`Table` 又需要 `sql_compiler.Planner` 来生成候选集。javac/Maven 下完全合法，只是意味着这两个包无法独立编译。若将来要引入 JPMS（`module-info.java`），需要先把 `Planner`/`ExprEvaluator` 挪进 `engine.table.plan` 破环。
- **`utils` 不是叶子。** 它包含 `AbstractCache`（被 `storage.buffer` 与 `engine.tx` 继承）和 `SubArray`，所以是共享基础设施，不是纯工具函数。

---

## 4. 逐层拆解

### 4.1 客户端与线缆

**入口 `cli/Launcher.java`**：三种模式——无参进交互 REPL，`-e` 单条执行（错误时 `System.exit(1)`，靠退出码而非异常表意），`-f` 脚本执行（极简分号切分器，跳过空行和 `--` 注释行）。

**协议 `engine/net/`**，四类帧：

| type | 名字 | payload |
|---|---|---|
| 0x01 | REQUEST | `[sqlLen:4][sql:utf8]` |
| 0x02 | OK | `[rowsAffected:8][elapsedNanos:8][msgLen:4][msg:utf8]` |
| 0x03 | RESULT_SET | 见 README；每行带 **null bitmap**，不是哨兵值 |
| 0x04 | ERROR | `[codeLen:4][code:utf8][msgLen:4][msg:utf8]` |

帧头是 `[0x5A 'Z'][0x44 'D'][type:1][len:4]`，`len` 是大端定长前缀——**不是换行分隔**，所以 SQL 里带换行、分号都安全。所有字符串在线上都是「长度前缀 + UTF-8」，`null` 字符串会被编码成空串（`nullToEmpty`），因此「空消息」和「无消息」在协议层不可区分。

**没有握手、没有鉴权**：客户端发出的第一帧就是 REQUEST。

**REPL `cli/Shell.java`** 里有几个不显眼但要命的细节：

- `jlineParser.setEscapeChars(null)` —— 不设这行，JLine 会把 `\` 当转义符吃掉，元命令全部失灵。
- 元命令只在**输入缓冲为空**时识别，否则 SQL 字符串字面量里的 `\` 会被误判。
- `\dt` / `\stats` / `\d t` **不是服务端命令**，是客户端改写成的 SQL（`show` / `show stats` / `desc t`）；`\h` 是本地打印，完全不走网络。
- 提示符状态只是启发式：服务端返回 OK 时看消息是不是 `"begin"`，出错时沿用上一次状态。

### 4.2 SQL 编译器

**`Tokenizer.java`** 是按字节扫描的，带一个 token 的前瞻。三件事值得记住：

- **没有反斜杠转义。** 转义只有 SQL 标准的引号加倍（`'it''s'`）。`"` 和 `'` 都当引号字符。
- **负号不是数字字面量的一部分。** `-5` 被切成 `-` 和 `5`，一元负号在 Parser 里处理。
- 引号内容按字节累积、最后一次性 `new String(bytes, UTF_8)` 解码，所以中文/emoji 不会在词法阶段被切坏。

**`Parser.java`** 是递归下降，入口签名是 `public static Object Parse(byte[] statement)` —— 注意返回 `Object` 而不是公共基类，`Executor` 用 `instanceof` 分发。

表达式优先级链由三个方法递归实现：`parseExprOr` → `parseExprAnd` → `parseExprNot` → `parseExprPred`，括号在 `parseExprPred` 顶部处理。

**AST `sql_compiler/statement/`（23 个类）** 分两组：

- 表达式：`Expr`（接口，只有一个 `repr()`）、`LogicalExpr`(and/or/not)、`CompareExpr`(= != < <= > >= is null/is not null)、`ColumnRef`、`Literal`、`InExpr`、`BetweenExpr`、`LikeExpr`
- 语句：`Begin`(带 `isRepeatableRead`)、`Commit`、`Abort`、`Create`、`Drop`、`Desc`、`Show`、`Stats`、`Insert`、`Select`、`Update`、`Delete`

还有一组**遗留节点** `Where` / `SingleExpression` / `OrderItem`：`parseSelect` 等会同时填充新的 `expr` 和旧的 `where`（`exprToLegacyWhere`），旧结构只有 `Table.read`（老路径）和 `Parser` 单测还在用。读代码时看到 `where` 字段可以当它是历史包袱。

`Literal` 只存原始字符串 + `isNull` 标志，**类型转换延迟到执行期**由 `Field.string2Value` 完成——这是刻意设计，一个 `Literal` 类就覆盖了全部六种类型。

**`Planner.java`** 的核心契约写在类注释里，值得原文引用：返回的 uid 集合是**超集**（一定包含全部匹配行，可能多），`null` 表示**无法用索引收窄**。

递归规则：

| 表达式 | 处理 |
|---|---|
| `NOT` | 一律 `null`（不尝试用索引） |
| `AND` | 两边都可收窄 → 交集；一边为 `null` → 返回另一边（超集仍安全） |
| `OR` | 任一边为 `null` → 整体 `null`（超集与无界集求并仍无界） |
| `=` | 索引点查 |
| `< <= > >=` | 索引范围查，**但仅当字段是「有序索引」** |
| `!=` / `LIKE` / `IS NULL` | `null`（收窄不了） |
| `IN` / `BETWEEN` | 非取反且有索引才收窄 |

**有序索引闸门**（`orderedIndex`）是理解本项目的关键：`string` 用哈希做 key、`float64` 用 IEEE 754 位模式做 key，两者都**不保持 SQL 大小顺序**，所以范围谓词对它们一律回退全表扫描，只有等值（`=` / `IN`）可用。这是正确性优先的取舍，不是遗漏。

**`ExprEvaluator.java`** 分两趟，这是刻意的：

- `validate(expr)` 先跑一遍，检查所有列名存在、所有字面量能按列类型解析、`LIKE` 只用于字符串列。**空表和短路逻辑下也要报错**，所以不能靠 `eval` 顺带发现。
- `eval(expr, entry)` 才是逐行判定。`AND`/`OR` 直接靠 Java 的 `&&`/`||` 短路；`LIKE` 用手写的双指针回溯匹配（`%` 任意串、`_` 单字符），刻意不用正则以避免转义边界问题。

### 4.3 表与执行

**`engine/Executor.java`** 是语句分发中枢，每个连接一个实例，持有 `long xid`（0 表示不在显式事务中）。

事务语句在任何表操作之前内联处理，且消息文本被客户端依赖：`begin` → `Package.ok("begin", 0)`。

**隐式事务**是这里最容易看漏的设计：

```java
if (xid == 0) { tmpTransaction = true; xid = tbm.begin(new Begin()).xid; }  // 裸 SQL 自动开事务
... finally { tmpTransaction ? (出错 ? tbm.abort : tbm.commit) : ... }
```

后果：**只有隐式事务会在出错时自动回滚**。在显式 `begin` 之后一条语句报错，`Executor` 的 `catch` 不碰 `xid`，事务**保持打开**，直到客户端发 `abort`、断开连接（`Executor.close()` 兜底 abort）为止。用惯了其他数据库的人在这里会踩坑。

另外两个小瑕疵：`TableManager.delete/update` 返回 `byte[]`，行数靠 `parseTrailingCount` 解析字符串尾巴得到，解析失败返回 `-1`；`ResultSet.note`（本该显示 "index scan on age"）**服务端从没调用过 `setNote`**，所以表格页脚永远不会出现索引提示。

**`engine/Server.java`** 的线程模型：`ThreadPoolExecutor(10, 20, 1s, ArrayBlockingQueue(100), CallerRunsPolicy)`。`CallerRunsPolicy` 意味着过载时由 accept 线程亲自执行连接（背压），而不是丢包。`shutdown()` 用 `AtomicBoolean` 做一次性守卫。

**`engine/table/Table.java`** 是三件事的集合体，读的时候要分清：

| 方法 | 角色 |
|---|---|
| `readForResultSet` | **真正在跑的查询路径**（§2 的六步） |
| `resolveCandidates` + `scanRows` | 候选集来源：优先 `RowDirectory.scan()`，旧库回退到任一索引字段的全范围 |
| `read` / `parseWhere` / `calWhere` / `Field.calExp` | **遗留路径**，要求 WHERE 字段必须有索引（否则 `TB-0003`），仅测试与兼容使用 |

**行目录（`RowDirectory.java`）是这套实现里非常关键的自研补丁**：一个持久化单链表，根记录存头 uid，节点是 `[rowUid:8][nextUid:8]`，**全部用 `SUPER_XID` 写入**（这样业务事务回滚不会破坏链）。它存在的理由是「无索引表也必须能全表扫描」——原版实现靠「随便找一个索引字段扫全范围」来枚举行，没有索引的表就查不到数据。`append` 是头插，`scan` 结束时 `reverse` 还原插入顺序，避免无序 SELECT 输出反着来。

**`update` 的实现值得单独看**：VM 没有 update，所以是「`vm.delete(xid, uid)` 标记旧版 → 插入新行 → 重建**全部**索引项指向新 uid」。旧索引项**故意不删**（见 `delete` 的注释）：回滚和并发快照仍可能需要那一版，可见性由 `vm.read` 判定。

### 4.4 事务与并发

这一层有三个类，职责要分清：

- `TransactionManager` —— 只管 **xid 状态**（活跃/已提交/已回滚），持久化在 `.xid` 文件。
- `VersionManager` —— **MVCC 语义**：读哪个版本、删除怎么表示、什么时候自动回滚。
- `LockTable` —— **写-写冲突**的互斥与死锁检测。

**没有版本链。** 这是最容易误解的一点。`Entry` 的字段只有 `uid` / `dataItem` / `vm`，磁盘上 `Entry` 的布局是 `[XMIN:8][XMAX:8][payload]`，**没有任何 next/prev 版本指针**。

那「旧版本」在哪？答案是：**旧版本就是那条记录本身**。删除 = 把 `XMAX` 原地写成当前 xid（`Entry.setXmax`），记录既不移动也不释放。之后：

```
候选 uid 集合（来自索引 / 行目录）
        ↓  逐个
Entry.loadEntry(uid) → Visibility.isVisible(tm, t, e) ?
        ↓ 可见                                     ↓ 不可见
   返回数据                                  vm.read 返回 null，调用方跳过
```

所以版本「链」是隐式的：同一逻辑行的多个历史版本是**若干个独立的 DataItem**，靠候选集合枚举 + 逐条可见性过滤来筛选。代价是历史数据与索引项无限增长（没有 VACUUM），好处是实现极简。

**隔离级别是一个裸 `int`**（0 = Read Committed，1 = Repeatable Read），由 `Begin.isRepeatableRead` 转成 `level` 传进 `vm.begin(level)`。RR 在 `begin` 时把当时 `activeTransaction` 的 keyset 拷进 `Transaction.snapshot`。

`Visibility` 是单次分派，不是两趟遍历：

- RC：每次都重新问 `tm.isCommitted(xmin)`，所以并发提交立刻可见。
- RR：额外要求 `xmin < xid && !t.isInSnapshot(xmin)`，即「发起时还活跃的事务写的版本一律看不见」。

**真正是「两阶段」的地方在 `VersionManagerImpl.delete`**，顺序不能颠倒：

1. `isVisible` —— 我**能不能看见**这个版本？看不见就别删。
2. `isVersionSkip` —— 有没有一个**我本该看见的更新版本**被我跳过了？有就别删。

只有第 1 步会放过「丢失更新」；只有第 2 步则会对看不见的行误判断。RC 刻意跳过第 2 步（`isVersionSkip` 在 `level == 0` 直接返回 false），因为 RC 本就允许版本跳跃。

**`LockTable` 只有排他锁，没有锁模式，也没有锁升级。** 数据结构是：

```
x2u : xid → 它持有的 uid 列表
u2x : uid → 持有它的 xid （一个 uid 只有一个持有者）
wait: uid → 等待它的 xid 列表   ← 注意键是 uid 不是 xid
waitLock: xid → 它阻塞用的 monitor
waitU: xid → 它在等的 uid
```

锁只在**删除路径**上获取（`insert` / `read` 完全无锁）。而且它不是「持有到提交」的互斥量——`delete` 里拿到锁立刻 `lock(); unlock()`，锁在这里只是**屏障**（阻塞到当前持有者结束）；真正的「占用」由 `u2x`/`x2u` 的条目表达，一直留到 `commit`/`abort` 时 `remove(xid)`。

死锁检测是从等待者出发的 DFS：`add` 时把请求者挂到队首，然后跑 `hasDeadLock()`，发现环就回滚自己的记录并抛 `DeadlockException`。**被牺牲的是「闭合环的那个请求者」**，不是全局挑选的牺牲者。`delete` 捕获后设 `t.err`、`internAbort(xid, true)`、置 `autoAborted`，之后该事务的任何操作都会重抛 `t.err`——**没有自动重试**，重试是调用方的事。也没有锁超时。

### 4.5 存储

**四个文件的分工**（注意 `db.bt` 不是 B+ 树，是 Booter 元数据）：

| 文件 | 内容 | 谁在管 |
|---|---|---|
| `db.db` | 8192 字节一页的页数组；页 1 是页头，页 2..N 存所有 DataItem（含 B+ 树节点） | `storage/buffer/PageCacheImpl` |
| `db.log` | WAL：`[XChecksum:4]{[Size:4][Checksum:4][Data]}*` | `storage/logger/LoggerImpl` |
| `db.xid` | `[xidCounter:8][每 xid 1 字节状态]`，0=活跃 1=已提交 2=已回滚 | `engine/tx/TransactionManagerImpl` |
| `db.bt` | `[firstTableUid:8][droppedCount:4]{[nameLen:4][name]}*`，原子写（临时文件 + `Files.move`） | `engine/table/Booter` |

**uid 的编码**是理解所有指针字段的前提：`uid = (pgno << 32) | offset`。一个 8 字节整数同时表达了「第几页、页内第几字节」，所以 B+ 树节点、行目录、索引项全都能用 `long` 互相引用。

**页格式**：

- `PageOne`（页 1）：只有 8 字节的 VC 区在偏移 **100**。正常打开时写随机 8 字节；干净关闭时把它们复制到偏移 **108**。下次打开比较 `[100,108)` 与 `[108,116)` 是否相等，**不等就说明上次是崩溃/被强杀**，触发恢复。这就是全部的崩溃检测机制——没有魔数。
- `PageX`（页 2+）：`[FreeSpaceOffset:2][数据...]`，分配是**单调追加**（`FSO += len`），**没有 slot 目录、没有页内空闲链表**。删除靠记录自己的 valid 标志做墓碑，空间不回收。

**缓冲池是引用计数，不是 LRU。** README 英文版写 "LRU PageCache"、代码注释写「通用 LRU 缓存」，都不准确。`AbstractCache` 的三个 `HashMap`（cache / references / getting）里没有任何 LRU 链表或时钟指针：

- `get` 命中 → 引用 +1；未命中 → 回源加载。`getting` 用来抑制惊群（别的线程正在加载就睡 1ms 重试）。
- `release` → 引用 -1，**归零就立刻 `releaseForCache` 并驱逐**。

也就是说，一个页只要没人持有就马上被丢掉——它更像「句柄表」而不是缓存，语句之间不保留任何页。`maxResource == 0` 表示无上限；PageCache 传的是 `mem / 8192`（默认 64MB → 8192 页），而 DataItem 缓存和 VersionManager 都传 0。满了又未命中会抛 `CM-0001 CacheFullException`。

**DataItem / Entry 的嵌套关系**要连起来看：

```
页内记录   [ValidFlag:1][DataSize:2][ XMIN:8 ][ XMAX:8 ][ payload ]
             └─ DataItem 层 ─┘   └──── Entry 层 ────┘
```

`DataItem.oldRaw`（`before()` 时拷贝的当前字节）是 **WAL 的 pre-image 缓冲**，供回滚和 update 日志使用，**不是版本指针**。

**WAL 与恢复**：日志记录以 1 字节类型开头，insert 是 `[type][xid:8][pgno:4][offset:2][raw]`，update 是 `[type][xid:8][uid:8][oldRaw][newRaw]`（两半靠长度对分）。`DataManager` 只在两处产生日志：插入路径和 `logDataItem`（由 `Entry.setXmax` 触发的 `after()` 调用）。

启动恢复（`storage/Recover.java`）严格三步，对应启动日志的三行输出：

1. **截断**：扫全日志取 `maxPgno`，把 `db.db` 截到那一页，打印 `Truncate to N pages.`
2. **Redo**：所有 `!isActive`（已提交**或**已回滚）的事务日志重放。`recoverInsert` 只在 `offset + len > FSO` 时才抬高 FSO，所以**重放是幂等、与顺序无关**的。
3. **Undo**：只收集仍 `isActive` 的事务日志，**按 xid 分组、逆序遍历**回滚，然后 `tm.abort(xid)`。注意 undo 一个 insert 是把 valid 标志置 1（逻辑删除），**不回收空间**。

**B+ 树**整体活在 `db.db` 里，没有独立文件。节点布局是**定长**的：

```
[LeafFlag:1][KeyNumber:2][SiblingUid:8][Son0:8][Key0:8][Son1:8][Key1:8]...
```

`BALANCE_NUMBER = 32`、`NODE_SIZE = 1067` 字节，最多 66 个槽位，叶子和内部节点**字节布局完全相同**，只差 `LeafFlag` 一位，因此搜索/插入/分裂代码共用。`key[i]` 是子树 `son[i]` 的最小键，最右键是 `Long.MAX_VALUE` 哨兵。

- 搜索从根下到叶子，然后**沿 sibling 链右行**收集范围结果——这就是范围扫描的实现方式。
- 分裂阈值是 64 键；分裂把右半拷进新节点，父节点插入返回的新分隔键。
- **删除只改叶子**（`leafRemove`），内部节点的分隔键**故意留在原地**（仍是有效的路由键），不做合并/再平衡，也不回收空间。键不唯一，所以定位时要同时匹配 key 和 uid。
- 所有结构性修改都包在 `dataItem.before()` / `after(SUPER_XID)` 里 —— 用超级事务写，因此**元数据操作不受业务回滚影响**。

---

## 5. 磁盘格式速查

| 对象 | 布局 |
|---|---|
| uid | `(pgno << 32) \| offset` |
| 页 1 的 VC | 偏移 100..107（开） → 复制到 108..115（干净关） |
| PageX | `[FSO:2][数据]`，追加分配 |
| DataItem | `[ValidFlag:1][DataSize:2][payload]`，总长 = size + 3 |
| Entry 载荷 | `[XMIN:8][XMAX:8][行数据]` |
| 行数据 | 按建表字段顺序拼接，**无偏移表、无 null bitmap** |
| Field 记录 | `[nameLen:4][name][typeLen:4][type][indexUid:8]`，0 = 无索引 |
| Table 记录 | `[name][nextUid:8][fieldUid:8...][-1:8][rowDirRootUid:8]` |
| 行目录节点 | `[rowUid:8][nextUid:8]`，根存头 uid |
| B+ 节点 | `[LeafFlag:1][KeyNumber:2][SiblingUid:8]{[Son:8][Key:8]}*` |
| 日志记录 | insert `[0][xid:8][pgno:4][offset:2][raw]`；update `[1][xid:8][uid:8][old][new]` |
| Booter | `[firstTableUid:8][droppedCount:4]{[nameLen:4][name]}*` |
| xid 记录 | 8 字节头 + 每 xid 1 字节状态 |

---

## 6. 关键设计取舍

| 取舍 | 为什么这么选 | 代价 |
|---|---|---|
| 索引只产候选集，正确性交给 Evaluator | 索引对 string/float64 无法保证有序，混用会出错 | 命中后仍要逐行过滤 |
| 删除 = 写 XMAX，索引项不删 | 回滚与并发快照仍需要旧版本 | 历史无限增长，无 VACUUM |
| 无显式版本链，标签放在记录里 | 实现简单，无需维护链指针 | 找旧版本要遍历候选集 |
| `RowDirectory` 持久化行目录 | 无索引表也必须能全表扫描 | 多一个元数据链，且随行数增长 |
| `DROP TABLE` 用墓碑名单 | VM 不支持原地改元数据，重排链会让所有表 uid 变化 | 磁盘空间不回收 |
| 干净关闭靠 VC 双写而非魔数 | 8 字节足矣，省一页预算 | 「相等」是唯一判据，无版本号 |
| 缓冲池归零即驱逐 | 实现最简单，绝不泄漏页 | 无热点保留，相当于没有缓存 |
| 锁只在删除路径 | 插入无需冲突检测（MVCC 天然隔离） | 丢失更新靠版本跳跃检测兜底 |

---

## 7. 容易踩的坑

### 7.1 语法与语义

- **回滚是 `abort`，不是 `rollback`。** 输入 `rollback` 会得到 `PR-0001 Invalid command!`，而且**不报事务错误**——事务还开着，后面所有操作都跑在同一个事务里。这是最容易造成「数据看起来重复了」的原因。
- **`create table` 不需要外层括号，但必须有 `(index ...)` 子句。** 正确写法是 `create table t id int32, name string, (index id);`。没有 `(index ...)` 会直接 `PR-0002`。README 里示例的外层括号是装饰性的。
- **`insert` 是位置参数，列数与建表列数必须完全相等**，否则 `TB-0005`。不支持 `insert into t (a, b) values (...)`。
- **SQL NULL 存不进去。** 行编码没有 null bitmap。`IS NULL` 谓词在求值层有语义，但 INSERT 路径不会把 `null` 变成 SQL NULL（token 是文本 `null`，进 `string2Value` 时对 int32 就是 `NumberFormatException`），而筛选/求值路径却把 Java `null` 当 SQL NULL——两条路径对 NULL 的处理不一致。
- 关键字大小写不敏感，**标识符大小写敏感**。

### 7.2 运行环境

- **交互客户端需要真实 TTY**（JLine `system(true)`）。管道/重定向下请用 `-e` / `-f`。
- **中文乱码**：需要 `chcp 65001` + `-Dstdout.encoding=UTF-8`。非 UTF 控制台可临时 `MINIDB_UNICODE=0` 退回 ASCII 表格；`MINIDB_COLOR=0` 或 `--no-color` 关颜色。
- **6 个测试类硬编码了 `/tmp/minidb`**（`BPlusTreeTest`、`DataManagerTest`、`ExecutorTest`、`LoggerTest`、`PageCacheTest`、`TransactionManagerTest`）。Windows 上 `C:\tmp` 不存在 → `Panic.panic` 调 `System.exit(1)` → **surefire fork 直接终止**，报 "VM terminated without properly saying goodbye"。排除这 6 个后 `mvn test` 正常。修法是换成 `System.getProperty("java.io.tmpdir")`。
- 数据目录 `data/` **未被 git 跟踪**，改动不可用 `git checkout` 还原。

### 7.3 读代码时

- `Table.read` / `parseWhere` / `calWhere` / `Field.calExp` 是**遗留路径**，线上不跑，别照着它理解查询流程。
- `Parser` 里的 `where` 字段与 `expr` 字段并存，前者是历史包袱。
- `ResultSet.note` 没人设置，页脚的「index scan」提示是预留位。
- 注释里的「LRU」字样不是真的 LRU（见 §4.5）。

---

## 8. 我要改 X，该看哪里

| 目标 | 入口 |
|---|---|
| 加一个 SQL 关键字 / 语句 | `Parser.Parse` 的 switch → 新增 `statement/` 节点 → `Executor.execDML` 分发 |
| 加一个 WHERE 运算符 | `Tokenizer` 出 token → `Parser.parseExprPred` → `statement/` → `Planner.planOrNull` → `ExprEvaluator.eval` |
| 加一种字段类型 | `Field.typeCheck` + `string2Value` + `value2Uid` + `value2Raw` + `parserValue` + `ColumnType` + `Table.mapColumnType` |
| 让范围查询用上索引 | `Planner.orderedIndex` 与 `Field.value2Uid`（需要真正保序的键编码） |
| 加快查询 / 让缓存真的像缓存 | `utils/AbstractCache`（现在是归零即驱逐） |
| 改协议 / 加字段 | `engine/net/Encoder` + `Package` 工厂（两端都要改） |
| 加客户端命令 | `cli/ui/MetaCommand.Kind` + `Shell.handleMeta`（或改成新的服务端语句） |
| 改恢复行为 | `storage/Recover.java`（redo/undo）与 `PageX.recoverInsert/recoverUpdate` 的幂等性 |
| 加真 NULL 支持 | `Table.entry2Raw` / `parseEntry`（要引入 null bitmap）与 `string2Value` 的调用约定 |
| 加鉴权 / TLS | `engine/Server.HandleSocket`（目前第一帧就是 REQUEST，无处插入握手） |

---

## 9. 已知限制与可动手方向

**限制**（README「已知限制」一节列出，此处说明成因）：

- 只有单表查询，无 JOIN / GROUP BY / 除 `COUNT(*)` 外的聚合——`Planner` 只产 uid 集合，不是完整的逻辑计划生成器。
- `float64` 索引按 IEEE 754 位模式排序，负数范围不正确（等值正确）；`string` 索引是哈希，完全无序。
- NULL 无法持久化。
- 协议无 TLS / 鉴权 / 限流。

**明确可做、且不破坏现有行为的**：

1. 修 6 个硬编码 `/tmp` 的测试类，让 `mvn test` 在任何平台全绿。
2. 把 `data/` 加进 `.gitignore`。
3. 实现 VACUUM 回收历史版本与孤儿索引项（`RowDirectory` 与 `delete` 的注释都预留了这个位置）。
4. 让服务端真正设置 `ResultSet.note`，把「索引扫描 / 全表扫描」暴露给客户端。
5. `TableManager.delete/update` 改为返回结构化行数，去掉 `parseTrailingCount` 的字符串解析。

---

## 附录：与 README 的出入

| README 说法 | 代码实际 |
|---|---|
| 英文版 "LRU PageCache"；中文版注释「通用 LRU 缓存」 | 纯引用计数，引用归零即驱逐，无 LRU / 无淘汰策略 |
| 文件清单中的 `db.bt` 容易被当成 B+ 树文件 | 是 Booter 元数据（表链表头 + 墓碑名单）；B+ 树节点存在 `db.db` 里 |
| `ResultSet.note` / 页脚 "index scan on age" | 服务端从未调用 `setNote`，该字段恒为空 |
| 中文版特性表写「引用计数缓存」 | 准确（英文版的对译不准确） |

其余 README 描述与代码一致，包括建表语法、错误码分段、协议帧格式与恢复行为。
