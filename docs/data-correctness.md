# 数据正确性修复与验证

本次基于 ZingDB 原版提交 `fb17ad24cd94a569c5125fd1f0fbff8f0e22979e` 修复已复现的数据正确性问题，保留上游项目归属。没有完成全部课程功能。

## 修复内容

| 问题 | 处理方式 |
|---|---|
| 中文、emoji 导致乱码及后继列错位 | SQL 和字符串统一 UTF-8，存储长度按字节计算 |
| 无索引表插入后查不到记录 | 新表维护持久化行目录，全表扫描不再依赖用户索引 |
| 负数记录遗漏、字符串/浮点范围结果错误 | 整数使用完整有符号范围；非保序索引的范围条件回退扫描 |
| DELETE/UPDATE 回滚后原记录消失 | 保留旧版本索引项，由 MVCC 判断可见性；新版本加入行目录 |
| 空表不检查 WHERE 字段和类型 | 扫描前遍历条件表达式校验，同时检查排序字段 |
| SQL 引号转义和空字符串解析错误 | 支持重复引号转义，区分带引号的文本和 NULL/减号 |
| 大整数比较精度丢失 | 整数按 long 比较，不转换为 double |
| B+ 树重复键分裂后漏查、最大整数插入异常 | 区分查询与插入的分支选择，修正最右节点边界 |
| 关闭缓存触发 ConcurrentModificationException | 遍历键快照后释放资源，并重置计数 |

行目录仅记录版本 UID，行的可见性仍由现有事务模块决定。更新、删除不会立即清除历史索引项。行目录、索引和历史版本会持续增长，目前没有 VACUUM，不能把该实现视为生产数据库。

## 验证结果

验证环境：macOS arm64、OpenJDK 26.0.1、Maven 3.9.16。

- 选定回归集合：77 个 JUnit 测试，0 failures、0 errors、0 skipped。
- 单独运行原版 BPlusTreeTest：1 个测试通过，包含 10,000 条逆序键插入与查询。
- 共 78 个不同的 JUnit 测试通过，包含新增的 20 个测试；并非全量压力测试通过。
- TCP 客户端黑盒验证：64 项检查中 60 项满足预期。中文、无索引表、负数、事务回滚、重启持久化均通过；强制终止服务后，未提交插入被撤销、未提交删除被恢复。
- 剩余 4 项属于课程功能缺口：标准 CREATE TABLE 写法、INSERT 指定列列表、常量算术表达式、对带引号数字的严格类型拒绝。现有实现仍会转换部分字符串为数字。

可在仓库根目录复现 JUnit 验证（首次运行需下载 Maven 依赖）：

```bash
mvn -B -ntp '-Dtest=ParserTest,ParserV2Test,EndToEndSqlTest,StatsTest,SlowQueryLoggerTest,PackagerTest,TableRendererTest,PageIndexTest,LockTableTest,LoggerTest,DataCorrectnessTest,CacheCloseTest,CacheTest,BPlusTreeBoundaryTest,PageCacheTest#testPageCache' test
mvn -B -ntp -Dtest=BPlusTreeTest test
```

实际验证另指定了独立 Maven 本地依赖目录并使用离线模式，避免改变机器默认缓存。TCP 黑盒探针和运行日志为本地验证材料，没有作为仓库测试提交。上述 JUnit 测试包含真实临时数据库上的重启验证，强制终止进程后的恢复另外由 TCP 探针验证。

未完成整套 ExecutorTest/DataManagerTest/TransactionManagerTest 压力测试，也没有做吞吐量承诺。

## 数据兼容范围

新建表元数据在字段 UID 后增加 `-1` 标记及行目录根 UID。新版可以识别旧格式；原版程序不能安全读取新格式数据库，请使用独立数据库文件验证并保留旧文件备份。

- 旧格式且有索引的表：使用完整有符号范围的索引扫描作为兼容路径。
- 旧格式且无索引的表：缺少可恢复的表到行映射，查询会明确报错，需要从原始数据重新建表导入；本次没有实现自动迁移。
- 旧版已经错误编码的中文数据：不会被自动修复，需从原始数据重新导入。

## 与实训要求的差距

这些修改解决了已复现的运行问题。显式逻辑执行计划、常量折叠/布尔化简及优化前后展示、LRU/FIFO 与命中统计、以特殊表存储系统目录、完整位置诊断与课程规定语法等仍需按 PPT 单独补齐。现有 Planner 主要生成候选 UID 集合，不能当作课程要求的完整逻辑计划生成器。
