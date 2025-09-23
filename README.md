# ZingDB

> 声明与致谢：ZingDB 借鉴并参考了开源项目 MyDB 的设计与实现，用于学习与实践。在此向 MyDB 及其作者致以感谢。基于此代码基础，ZingDB 将在后续持续进行深入的功能扩展与性能优化，所有原始版权归 MyDB 原作者所有。

ZingDB 是一个 Java 实现的简单的数据库，部分原理参照自 MySQL、PostgreSQL 和 SQLite。实现了以下功能：

- 数据的可靠性和数据恢复
- 两段锁协议（2PL）实现可串行化调度
- MVCC
- 两种事务隔离级别（读提交和可重复读）
- 死锁处理
- 简单的表和字段管理
- 简陋的 SQL 解析（因为懒得写词法分析和自动机，就弄得比较简陋）
- 基于 socket 的 server 和 client

## 运行方式

注意首先需要在 pom.xml 中调整编译版本，如果导入 IDE，请更改项目的编译版本以适应你的 JDK

首先执行以下命令编译源码：

```shell
mvn compile
```

接着执行以下命令以 /Users/tankenqi/Downloads/zingdb/zingdb 作为路径创建数据库：

```shell
mvn exec:java -Dexec.mainClass="top.tankenqi.zingdb.backend.Launcher" -Dexec.args="-create /Users/tankenqi/Downloads/zingdb/zingdb"
```

随后通过以下命令以默认参数启动数据库服务：

```shell
mvn exec:java -Dexec.mainClass="top.tankenqi.zingdb.backend.Launcher" -Dexec.args="-open /Users/tankenqi/Downloads/zingdb/zingdb"
```

这时数据库服务就已经启动在本机的 9999 端口。重新启动一个终端，执行以下命令启动客户端连接数据库：

```shell
mvn exec:java -Dexec.mainClass="top.tankenqi.zingdb.client.Launcher"
```

会启动一个交互式命令行，就可以在这里输入类 SQL 语法，回车会发送语句到服务，并输出执行的结果。

一个执行示例：

![](https://cdn.jsdelivr.net/gh/binwenwu/picgo_02/img/QQ_1758612882546.png)
