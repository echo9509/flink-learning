1. Flink 整体架构
2. Flink Runtime架构
3. Flink Runtime Master结构
4. Flink 运行模式
5. Flink TaskExecutor
6. Flink 作业提交运行过程
7. Flink 资源管理
8. Flink Share Slot
9. Flink 作业调度
10. Flink 错误恢复

**Flink整体架构**

Flink整体架构从下自上分为：

- 物理资源层
- Runtime统一执行引擎
- API层
- High-level API层

Flink可以运行在多种不同的环境中：

- 单进程、多线程运行
- Yarn集群
- K8S集群
- 各种云环境

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h520ghawetj20gz0d1n02.jpg)

针对不同的运行环境，Flink提供了一套统一的分布式作业引擎，就是上图的Runtime层。

**Flink Runtime架构**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h520ixx4tij20w40edwjp.jpg)


Flink Runtime采用了标准的Master-Slave架构：

- AM(AppMaster)：Master
- TaskManager

**Flink Runtime Master结构**

Flin Runtime Master包含三个主要组件（全部存在于AppMaster进程中）：

- Dispatcher：负责接收用户提交的作业，并为该作业拉起一个新的JobManager
- ResourceManager：负责资源的管理，整个Flink集群中只有一个
- JobManager：负责管理作业的执行，Flink集群中有多个作业，每个作业都有自己的JobManager

**Flin集群运行模式**

Flink集群主要有两种运行模式：

- Session模式：AM预先启动，Client直接与Dispatcher建立连接提交作业
- Per-Job模式：AM不会预先启动，Client首先向资源管理系统(Yarn、K8S)申请资源来启动AM，然后再向AM中的Dispatcher提交作业

Flink集群两种运行模式的特点：

- Session模式：共享Dispatcher和ResourceManager，共享资源(TaskExecutor)，适合规模小执行时间短的作业
- Per-Job模式：独享Dispatcher和ResourceManger，按需申请资源(TaskExecutor)，适合执行时间较长的作业

**Flink TaskExecutor**

Flink中TaskExecutor的资源是通过Slot进行描述，一个Slot一般可以执行1个具体的Task，但在一些情况下可以执行多个相关联的Task。


**Flink作业提交运行过程**

用户提交作业时，提交脚本会启动一个Client进程负责作业的编译和提交，该Client进程会将代码编译为一个JobGraph（该过程中还会进行检查和优化等工作，比如判断哪些Operator可以Chain到同一个Task中），最后Client会将产生的JobGraph提交到集群中运行。

在将作业提交到AM的Dispatcher后，Dispatcher首先会启动一个JobManager，然后JobManager会向ResourceManager申请资源启动作业中的具体任务，此时根据Flink运行模式的不同会有不同的逻辑：

- Session模式：ResourceManager已经有记录的TaskExecutor注册的资源，直接选取空闲资源进行分配
- Per-Job模式：ResourceManager会先向外部资源管理系统申请资源来启动TaskExecutor，然后等待TaskExecutor注册响应资源后再选贼空闲资源进行分配

ResourceManager在选择到空闲的Slot以后，就会通知TaskManager将该Slot分配给JobManager，然后TaskExecutor进行记录，会向JobManager进行注册。JobManager收到TaskExecutor注册上来的Slot便可以提交Task。

TaskExecutor收到JobManager提交的Task后，会启动一个新的线程执行该Task，Task启动后就开始进行计算，并通过数据Shuffle模块互相交换数据。

**Flink资源管理**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h53p28a4ogj20im0c7n0c.jpg)

Flink中的资源是有TaskExecutor的Slot进行表示。

- SlotManager：SlotManager属于ResourceManager，用于维护当前Flink集群中TaskExecutor上的Slot信息和状态，比如Slot是在哪个TaskExecutor，是否空闲等。
- SlotPool：SlotPool属于JobManager，用于缓存所有的Task请求和被分配给该JobManager的Slot，当有Slot被提供后，SlotPool会从缓存的请求中选择相应的请求和Slot。

当我们Flink JobManager为Task申请资源时，主要有以下过程：

1. 首先会去跟ResoureceManager申请Slots，然后根据集群的运行模式来决定是否开启新的TaskExecutor
2. 新的TaskExecutor启动后，会将自身的Slot信息注册给ResourceManager的SlotManager组件(5.register)
3. 此时SlotManager会从空闲的Slot中选取一个分配给Task
4. 分配完成以后，ResourceManager会向TaskManager发起RPC请求，要求将选定的Slot分配给JobManager(5.requestSlot)
5. TaskManager如果还没有执行过该JobManager的Task的话，它会与相应的JobManager建立连接，发起提供Slot的RPC请求(6. offset)
6. 当Task执行结束以后，都会通知JobManager其自身的执行状态，然后在TaskManager中将Slots标记为已占用未执行任务的状态

JobManager在Task任务完成以后，并不会立即释放Slot，而是经过当Slot在SlotPool中的时间超过指定的时间并未使用时（延迟释放），SlotPool才会发起释放请求释放该slot(7.release/cancel slot)，在释放过程中：

1. SlotPool首先会和TaskManager通信，告诉TaskManger我需要某个Slot我不再占有了
2. TaskManager在收到请求后，会通知ResourceManager某个Slot已被JobManager释放，同时SlotManager中会更新该Slot的状态，以便被其他的JobManager获取使用。

**通过Slot的延迟释放，避免如果直接将Slot还给ResourceManager，在任务异常结束后重启需要立即重新申请slot的步骤，可以将失败的Task尽快调度回原来的TaskManager进行执行，加快Failover的速度。**

除了正常的通信以外，TaskManager和ResourceManager及JobManager还会存在心跳信息来同步Slot的状态，避免了正常通信的消息丢失时各组件状态不一致的问题。

**Flink Share Slot**

Flink Share Slot指的是在一个Slot中可以运行多个Task，**每个Slot中可以部署来自不同JobVertex的Task**，这样可以提高Slot的资源利用率。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h559vcz9elj20tu0nu46v.jpg)


**Flink作业调度**

前面我们已经提到了，在提交作业时，我们的Client进程会将作业编译成一个JobGraph，JobGraph代表了作业的逻辑结构，当JobManager收到提交的作业以后，会根据JobGraph按照并发展开，从而得到实际的ExecutionGraph，ExecutionGraph是物理结构，JobManager实际维护的就是ExecutionGraph的相关数据结构。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h559zh5delj21ho11wkjl.jpg)

Flink的一个Job任务通常包含很多个Task，目前Task的调度方式主要有两种：

- Eager调度：Eager调度会在Job启动并且申请资源时将所有的Task调度起来，适用于流式作业
- LAZY_FROM_SOURCE：从Source开始，按照拓扑顺序依次将Task进行调度，适合批处理作业

**Flink错误恢复**

Flink的错误主要分为两类：

- Task执行出现的错误
- Flink的Master集群发生错误


对于Task错误的恢复策略主要有以下几种：

- Restart-all：直接重启所有的Task

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h55abga0ewj20zi0sa1fs.jpg)

借助Flink的Checkpoint机制，任务重启以后我们可以直接从上次的Checkpoint开始重新执行，Restart-all策略更适合流式处理作业。

- Restart-individual：直接重启出错的任务，只适用于Task之间没有数据传输的任务

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h55ac3d7o7j20zo0sejwy.jpg)

Flink的批处理作业没有Checkpoint机制，对于需要数据传输的作业，如果重启后从头开始计算将会造成性能问题，由于Restart-individual只适合Task之间没有数据传输的任务，所以为了解决这个问题，Flink集群引入了一种新的策略：

- Restart-region

在Flink批处理的Task中，数据的传输方式主要有两种：

- Pipeline：该方式的上下游Task之间直接通过网络进行传输数据，需要上下游同时运行
- Blocking：该方式上游的Task首先会将数据进行缓存，因此上下游的Task可以单独运行

基于上述两种传输方式，Flink根据ExecutionGraph中使用Pipeline传输数据的Task的子图叫做Region，从而将ExecutionGraph划分为多个Region。

基于上述特点，如果某个Region的Task发生执行错误，可以分两种情况进行考虑：

- Task本身的问题发生错误，可以只重启该Task所属的Region中的Task，这些Task重启后，可以直接拉取上游Region的缓存的输出结果进行计算

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h56927fbpmj218w0kudo1.jpg)

- 如果错误是由于读取上游结果出现问题，那么除重启本Region的Task以外，还需要重启上游Region的Task重新产生相应的数据。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5696xng26j21900kgdnx.jpg)



Flink的Master集群发生异常，Flink支持多个Master做备份，当主Master发生宕机时，备份的Master可以通过Zookeeper进行选主，保证任一时刻只有一个Master运行。针对Master集群发生故障时的作业恢复，目前Flink是直接重启整个作业。