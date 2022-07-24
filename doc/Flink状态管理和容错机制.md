1. 什么是有状态的计算
2. 使用状态的场景
3. 为什么需要状态管理
4. 理想状态管理的特点
5. Flink状态分类
6. Managed State分类
7. Keyed Stated特点
8. Operator State特点
9. Keyed Stated的具体分类
10. 如何保存状态
11. Checkpoint和Savepoint区别
12. 状态保存在哪里


**什么是有状态的计算？**

有状态计算指的就是程序在计算过程中，需要将数据（状态）存储在本地存储或者外部存储中，以便下一次进行计算时获取使用，比如统计Nginx某个地址的调用次数，需要在每次计算时
不停的进行累加，并且将结果进行存储以便下次累加获取使用。

**使用状态的场景**

- 去重：上游系统数据会重复，落到下游系统时根据主键进行去重，需要将所有主键都记录下来，新的数据到来时需要判断主键是否已经存在
- 窗口计算：每分钟Nginx的访问次数，09:00~09:01这个窗口的数据需要先存入内存，等到09:01到来时将数据进行输出
- 机器学习/深度学习：训练的模型和当前模型的参数也是一种状态
- 访问历史数据：例如和昨天数据进行对比，如果每次从外部去读消耗资源比较大，所以可以把这些历史数据放入状态中做对比

**为什么需要状态管理？**

流式作业一般需要7*24小时不间断的运行，在宕机恢复时需要保证数据不丢失，在计算时要保证计算结果准确，数据不重复，恰好计算1次，为了达到上述这些目的，我们就需要对
程序运行过程中的状态进行管理。

**理想状态管理的特点**

- 易用：需要提供丰富的数据结构、多样的状态组织形式以及简洁的扩展接口
- 高效：实时作业需要需要更低的延迟，因此在状态保存和恢复时，需要保证处理速度；同时在进行横向扩展时不能影响作业本身的处理性能
- 可靠：状态需要可以被持久化，保证宕机后可以恢复

**Flink状态分类**

| |Managed State|RawState|
|---|---|---|
|状态管理方式|Flink Runtime自动管理：自动存储、自动恢复、内存优化|用户自己管理，需要自己序列化|
|状态数据结构|已知的数据结构：Value、List、Map等|字节数组byte[]|
|推荐使用场景|大多数情况下可以使用|自定义Operator时使用|

**Managed State分类**

Managed State主要分为两类：

- Keyed State
- Operator State

**Keyed State特点**

- 只能使用在KeyedStream算子中
- 一个Key对应一个State，一个Operator实例可以处理多个key，访问相应的多个State
- 随着并发改变，State会随着key在多个Operator实例间迁移
- 需要通过RuntimeContext访问，因此Operator必须是一个RickFunction
- 支持多样化的数据结构：ValueState、ListState、ReducingState、AggregatingState、MapState

**Operator State特点**

- 适用所有的算子，常用于source
- 一个Operator实例对应一个State
- 并发改变时，有两种重新分配方式可以选择：均匀分配或者合并后每个得到全量
- 实现CheckpointedFunction或ListCheckpointed接口
- 支持的数据结构：ListState


**Keyed Stated具体分类**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h4i51e3hkfj20tq0crad8.jpg)

- ValueState：存储单个值
- MapState：数据类型为Map，在State上有put和remove等方法
- ListState：数据类型为List
- ReducingState：Reducing的add方法不是将当前元素追加到列表，而是直接更新进Reducing的结果中
- AggregatingState：AggregatingState和ReducingState的区别是在访问接口，Reducing的add和get的元素都是同一个类型，但是Aggregating输入的是IN，输出的是OUT

**如何保存状态**

保存状态依赖Checkpoint和Savepoint机制，Checkpoint是在程序运行过程中自动触发，Savepoint需要手动触发。

如果从Checkpoint进行恢复，需要保证数据源支持重发，同时Flink提供了两种一致性语义（恰好一次或者至少一次）。

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.enableCheckpointing(1000L);
CheckpointConfig checkpointConfig = env.getCheckpointConfig();
checkpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
checkpointConfig.setMinPauseBetweenCheckpoints(500L);
checkpointConfig.setCheckpointTimeout(60000L);
checkpointConfig.setMaxConcurrentCheckpoints(1);
checkpointConfig.setExternalizedCheckpointCleanup(ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
```

- enableCheckpointing：设置Checkpoint的间隔时间，单位ms
- setCheckpointingMode：设置Checkpoint的模式，如果设置了EXACTLY_ONCE，则需要保证Barries对齐，保证消息不会丢失也不会重复
- setMinPauseBetweenCheckpoints：设置两次Checkpoint中间的等待时间，通过这个可以防止Checkpoint太过频繁导致业务处理速度下降
- setCheckpointTimeout：设置Checkpoint的最大超时时间，上面代码表示如果Checkpoint超过1min，则超时失败
- setMaxConcurrentCheckpoints：表示同时有多少个Checkpoint在做快照
- setExternalizedCheckpointCleanup：用于设置任务在Cancel时是否需要保留当前的Checkpoint，RETAIN_ON_CANCELLATION当作业取消时保留作业的checkpoint，该情况下需要手动清除该作业
的Checkpoint，DELETE_ON_CANCELLATION作业取消时删除作业的Checkpoint，仅当作业失败时保存Checkpoint
  
**Checkpoint和Savepoint区别**

| |Checkpoint|Savepoint|
|---|---|---|
|触发管理方式|Flink自动触发管理|用户手动触发管理|
|用途|Task发生异常时快速恢复|有计划地进行备份，作业停止后可以恢复，比如修改代码、调整并发|
|特点|轻量；自动从故障恢复；作业停止后默认清除|持久；标准格式存储，允许代码或配置发生改变；手动触发从Savepoint的恢复|

**状态保存在哪里？**

状态保存有三种方式：

- MemoryStateBackend
- FsStateBackend
- RocksDBStateBackend

MemoryStateBackend在Checkpoint是基于内存保存状态，该状态存储在TaskManager节点(执行节点)的内存中，因此会受到内存容量的限制（默认5M），同时还要受到akka.framesize的限制
(默认10M)。 Checkpoint保存在JobManager内存中，因此总大小不能超过JobManager的内存，只推荐本次测试或无状态的作业使用。

FsStateBackend是基于文件系统保存状态的，状态依旧保存在TaskManager中，因此State不能超过单个TaskManager的内存容量，Checkpoint存储在外部文件系统中（比如HDFS或本地），打破了JobManager内存的限制，
但是总大小不能超过文件系统的容量，推荐状态小的作业使用。

RocksDBStateBackend，首先RocksDB是一个K-V的内存存储系统，当内存快满时，会写入到磁盘，RocksDB也是唯一支持增量Checkpoint的Backend，这说明用户不需要将所有状态都写入进去，可以
只将增量改变的状态写入即可。Checkpoint存储在外部文件系统，因此State不能超过单个TaskManager内存+磁盘总和，单key最大为2GB，总大小不超过文件系统的容量即可，推荐大状态作业使用。
