1. Checkpoint和State的关系
2. Flink State
3. Statebackend分类
4. Checkpoint机制
5. EXACTLY_ONCE
6. RocksDB增量Checkpoint


**Checkpoint和State的关系**

Checkpoint是从source触发到下游所有节点的一次全局操作。

State就是Checkpoint的持久化备份的数据。

**Flink State**

关于Flink State的分类和特点，点击[Flink状态管理](https://mp.weixin.qq.com/s?__biz=MzU4ODM1NjY5NQ==&mid=2247486888&idx=1&sn=3e77252483b615880448646228909eb7&chksm=fddf4090caa8c986f4fe4e0dc0a3942786c875f062cb1cd29a2e421c94e9f66f1eb9936f7f2b&token=672587436&lang=zh_CN#rd)。

**Statebackend分类**

Flink内置了三种Statebackend，MemoryStateBackend和FsStateBackend运行时都是存放在Java Heap中，只有Checkpoint时FsStateBackedn才会讲数据以文件格式持久化到远程存储上，RocksDBStateBackend则是使用RocksDB对State进行存储。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5eazpv78lj211y0hzakm.jpg)


对于HeapKeyedStateBackend来说，有两种实现：

- 支持异步Checkpoint(默认)：存储格式为CopyOnWriteStateMap
- 仅支持同步Checkpoint：存储格式为NestedStateMap

对于MemoryStateBackend使用HeapKeyedStateBackend时，Checkpoint序列化数据阶段最多只能保存5M数据。

对于RocksDBKeyedStateBackend，每个State都会存储在一个单独的column family中，其中keyGroup、key和namespace（默认是VOID，通常使用window时会有值）进行序列化存储在DB作为key。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ebjbt1oej20w009gq6w.jpg)

**Checkpoint机制**

1. JobManager中的Checkpoint Coordinator是整个Checkpoint的发起者，下图是由两个Source和一个Sink组成的Flink作业，最右侧是持久化存储，在Checkpoint的第一步则是需要我们的Checkpoint Coordinator向所有的Source发起Checkpoint。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ebui75b8j212c0bhjv0.jpg)

2. Source节点向下游广播Barrier，Barrier是实现分布式快照算法的核心，下游的Task只有收到所有的input的Barrier以后才会执行相应的Checkpoint。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ec0rsqu1j21180ca7a2.jpg)

3. 当Task完成State备份以后，会将备份数据的地址（state handle）通知给Checkpoint Coordinator。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ec2q3fjwj20ze0db7as.jpg)

4. 下游的Sink节点收集齐上游两个input的Barrier以后，会执行本地快照，下图是RocksDB增量Checkpoint的流程，首先RocksDB会全量刷新数据到磁盘上(红色大三角)，然后Flink会从中选择没有上传的文件进行持久化备份(紫色小三角)。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ec69p427j211j0dc7az.jpg)

5。 同样的，Sink节点在完成自己的Checkpoint以后，会通知Checkpoint Coordinator备份数据的地址(state handle)。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ec7wm7o5j20ya0bqdm3.jpg)

6. 最后当Checkpoint Coordinator收集齐所有的Task的State Handle以后，就可以认为此次Checkpoint完成了，此时会向持久化存储中再备份一个Checkpoint meta文件。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ec9n7gluj21060d6dmr.jpg)

**EXACTLY_ONCE**

通过Flink的Checkpoint机制我们仅能做到计算过程中的EXACTLY_ONCE，Source和Sink的EXACTLY_ONCE还是需要数据源本身和Sink的支持。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ecz7o3ygj211v08tn1q.jpg)

Flink为了实现EXACTLY_ONCE，需要通过一个input buffer将在对齐阶段收到的数据缓存起来，等到对齐完以后（上游的Barrier全部到来）再将数据发往下游进行处理。

**RocksDB增量Checkpoint**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5ed3kq326j20sd0g9gq9.jpg)

1. 本地的snapshot目录创建当前DB内容的备份
2. 与上一次成功的checkpoint本地sst文件列表做对比，将不在其中的文件上传到外部存储上
3. 所有文件都会重命名防止冲突
4. 包含了所有新旧文件的state handle返回给checkpoint coordinator

