1. 背景
2. 原因分析定位
3. 优化思路
4. 实现方案
5. SinkTaskProcessor
6. AbstractAsyncRichSinkFunction
7. AsyncSinkTaskRunnable
8. 源码


**背景**

在Flink的使用中，我们有一个场景是基于阿里的SLS进行消费，对一些监控指标进行清洗和采集，存入后面的TSDB，在第一次上线以后，系统正常运作无异常，随着指标数量的增加，
有一天收到了SLS消费延迟的告警，于是有了今天关于Sink的异步优化。

**原因分析定位**

1. 收到报警信息以后，由于是SLS的position推进缓慢，我首先对FlinkLogConsumer这个Source的配置进行了调整，提高了拉取的数量和频率
2. 配置修改完成以后，发现系统并未改善，SLS消费延迟很严重
3. 此时，我又修改了Flink任务的并发度，发现还是未改善，由于资源的限制我并不可能无限制提高Flink任务的并发度
4. 在修改完Flink任务的并发度还未改善以后，已经有预感是代码的写法问题了
5. 由于中间的算子计算流程并不复杂，已经推测大概率是最后的Sink问题，因为最后的Sink需要通过网络与TSDB交互
6. 在整个流程加入日志，进行最终问题确认，最终确认了是由于Sink处理缓慢，处理速率远远低于Source生产的速率，形成了反压现象（需要对上游进行限速）

**优化思路**

原来的Sink是收到一条数据，就请求TSDB接口进行数据写入，所有接口都是同步顺序执行，因此需要将Sink中的处理逻辑改为异步操作。

1. 使用数据缓存队列，原始的Sink线程只将数据存入缓存队列，并不升级处理
2. 使用线程池，开启多线程从数据库缓存队列中获取数据进行处理
3. 由于我们都会开启Checkpoint机制，当Checkpoint触发的时候我们需要确保我们数据缓存队列中的数据不丢失，关于这个可能会有两种选择，一种Checkpoint的时候讲数据缓存队列一并保存
下来，但这种假设队列里面的数据很多的话，State将会变的非常大；另一种就是在Checkpoint触发的那一刻，我让Checkpoint等我，等我的线程将数据缓存队列消费完毕以后再去执行，这种基于
我现在的理解是比较推荐的，但是要注意控制好队列的数量和线程池，避免Checkpoint等待太久从而失败（默认Checkpoint的执行超时失败时间是10min）
   
**实现方案**

1. 缓存队列我们使用LinkedBlockingQueue，用来保证多线程存取数据的安全性
2. 如何让Checkpoint触发的时候等待我们的线程将数据消费完以后再去执行呢？这里需要借助CheckpointedFunction接口snapshotState，该方法会在Checkpoint之前调用（钩子），
那么如何让Checkpoint停下来呢，我们这里使用CyclicBarrier(栅栏计数器)，不了解的可以自己去学习一下（这也是多线程编程常用的类）
   
举个简单的例子说明栅栏计数器的作用， 就拿公司开会来说，首先你都会定好哪些人参会，等到参会的人都到齐了才可以真的开始会议，有一个人没到，会议室的人都必须等他来。

栅栏计数器就是你设置一个数字（多少人参会），每当一个人走进会议室坐下（相当于调用await()方法）该数字就减1，并且需要等待其他与会人员的到来，直到所有人来了以后（该数字变为了0），
会议就可以正式开始了，开始各抒己见。

**SinkTaskProcessor**

SinkTaskProcessor是一个接口，这个是由使用方必须实现的一个接口，里面的process(T data)方法代表实际的处理逻辑，我这里就是往TSDB写入数据

**AbstractAsyncRichSinkFunction**

AbstractAsyncRichSinkFunction是一个抽象类，该类继承RichSinkFunction<T>并且实现CheckpointedFunction

- open方法：重写RichSinkFunction的open方法，并且增加final修饰（不允许子类再重写了），主要用来初始化线程池，数据缓存队列，CyclicBarrier和SinkTaskProcessor，提交处理任务
- invoke方法：重写RichSinkFunction的invoke方法，并且增加final修饰（不允许子类再重写了），用来往数据缓存队列存放数据
- snapshotState：实现CheckpointedFunction的snapshotState，并且增加final修饰（不允许子类再重写了），用来让Checkpoint等待数据缓存队列中的数据消费完毕，保证数据不丢失
- SinkTaskProcessor<T> getTaskProcessor()：抽象方法，需要每个子类自己实现，用于获取真正的Sink处理器

**AsyncSinkTaskRunnable**

AsyncSinkTaskRunnable是我们提交到线程池中的任务，该类被定义为final不可以被继承，AsyncSinkTaskRunnable做的事情主要就是从数据缓存队列里获取数据，并交给SinkTaskProcessor
进行处理，在获取数据的时候需要设置了500ms的超时时间（超时未取到说明数据缓存队列已经被消费完了），此时需要对CyclicBarrier进行检查：

- 调用getNumberWaiting方法，如果大于0，说明Checkpoint被触发了，此时需要执行CyclicBarrier.await方法，主动上报告诉Checkpoint，当所有线程上报完毕以后，Checkpoint就可以执行了
- 调用getNumberWaiting方法，如果小于等于0，说明Checkpoint没有触发，此时线程继续下一轮循环去数据缓存队列尝试取数据进行消费

**源码**

[Sink反压优化的源码](https://github.com/echo9509/flink-learning)

实现在package cn.sh.flink.learning.sink.async。

cn.sh.flink.learning.sink.async.SlowlyRickSinkTestFunction模拟了一个处理比较慢的Sink逻辑（这里记住真正处理处理数据的是SinkTaskProcessor）。

