1. 获取TableEnvironment
2. 标识符
3. 创建表
4. Table
5. 输出表
6. 翻译与执行查询
7. 解释表


**TableEnvironment**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h4ozlt5jmaj20mj0bhwg4.jpg)

从上图可以看出StreamTableEnvironment是对TableEnvironment的扩展，在获取到Table的执行环境以后就可以注册TableSource，执行select、insert或者其他聚合操作，同时
我们也可以将执行的结果进行Sink。

通过TableEnvironment我们可以创建临时表和永久表：

- 临时表：临时表只与单个Flink会话的生命周期相关，通常保存在内存中且在创建它的Flink会话持续期间存在，对其它会话不可见，不予任何catalog或者数据库绑定
- 永久表：一旦创建永久存在，需要与catalog绑定从而来维护表的元数据，对任何连接到catalog的Flink会话持续可见，直到表被删除

当使用与永久表相同的标识符去注册临时表时，临时表会永久屏蔽永久表，只要临时表存在，永久表都无法访问。

**标识符**

Flink中表的注册都是通过三元标识符进行，标识符包括：

- catalog名
- 数据库名
- 表名


**创建表**

- 虚拟表：对应Table API的视图
- 表：通过connector声明来创建table，connector用来描述存储表数据的外部系统，比如HDFS、Kafka等

**Table**

Table是Flink中的一个接口，代表着在表上的操作，比如select、where、groupBy等。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h4p7uhd3vlj20wg0dn43y.jpg)

**输出表**

Table可以通过写入TableSink进行输出，TableSink是一个接口，支持多种文件格式(CSV、Apache Parquet等)、存储系统（JDBC、HBase、ES）或者消息系统。


**翻译和执行查询**

不论数据源是流式输入还是批式输入，TABLE API查询都会被转换成DataStream程序，查询被翻译成两个阶段：

1. 优化逻辑执行计划
2. 翻译成DataStream程序

TABLE API查询会在以下情况被翻译：

- TableEnvironment.explainSql()：用来执行一个SQL语句
- Table.executeInsert()：将一个表的内容插入到目标表
- Table.execute()：将一个表的内容收集到本地
- StatementSet.execute()：Table（通过StatementSet.addInsert()输出到某个Sink）和INSERT语句(StatementSet.addInsertSql())会被先缓存到StatementSet中，StatementSet.execute()被调用时，所有的Sink都会被优化成一张DAG图
- Table被转换为DataStream以后，在调用execute时会立即翻译

**解释表**

Table.explain()或者StatementSet.explain()可以用来解释计算Table的逻辑和优化查询计划，返回一个描述三种计划的字符串：

1. 关系查询的抽象语法树（未优化的逻辑查询计划）
2. 优化的逻辑查询计划
3. 物理执行计划

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h4pamv3ywaj20jq09m40r.jpg)
