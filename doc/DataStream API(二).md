1. DataStream
2. KeyedStream
3. ConnectedStreams
4. WindowedStream
5. WindowAssigner
6. Evictor
7. Trigger
8. Time和WaterMark

[GitHub源码](https://github.com/shysh95/flink-learning)

**DataStream**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h40tn4gxkuj20s10dutdw.jpg)

DataStream作为我们最基础的流处理类，我们可以通过一些方法可以其转换为其他形式的流，其中上图中的SplitStream在Flink 1.13.1版本已经进行了移除，现在DataStream中的方法如下图：

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h41r9knuzcj20gy0uxall.jpg)

- connect：将两个流进行合并，形成ConnectedStream
- keyBy：在逻辑上将一个流按照某种规则划分为不同的分区，具有相同规则的记录被分配到同一个分区
- windowAll：在DataStream上定义Window，Window会根据某些特征对流事件进行分组

**KeyedStream**

KeyedStream是在普通的DataStream基础上，我们通过一定的规则将在逻辑上将一条流划分为不同的分区，具有相同规则的记录会被分配到同一个分区，KeyedStream上的操作如下图：

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h41r165a6fj20ik0ljguw.jpg)

- sum：对于每一个分区(key)，根据某个属性或数据的位置进行求和
- max：对于每一个分区(key)，根据某个属性或数据的位置求最大值
- maxBy：作用等同于max，但是他又一个额外的参数，如果该参数设置为true，当比较的的值相等的时候取第1个到来的元素
- reduce：对于每一个分区(key)，将当前的数据和最后一次reduce得到的元素进行组合然后输出新的元素

KeyedStream的示例代码见[GitHub源码](https://github.com/shysh95/flink-learning) cn.sh.flink.learning.daemon.KeyedStreamDaemon

**ConnectedStreams**

通过DataStream的connect方法我们可以将两个流进行合并，合并后的流就是ConnectedStreams，ConnectedStreams支持的操作如下图：

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h41v673wxpj20mf0e2gt4.jpg)

在被Connect的两个流的处理逻辑之间我们可以共享状态，并且我们在进行计算时可以为每个流定义他自己的操作：

- map和flatMap：在ConnectedStream上进行map和flatMap操作，同时流也变回了DataStream
- keyBy：在ConnectedStream里对两个流分别进行keyBy，形成两个逻辑分区的KeyedStream

ConnectedStreams的示例代码见[GitHub源码](https://github.com/shysh95/flink-learning) cn.sh.flink.learning.daemon.ConnectStreamDaemon

**WindowedStream**

keyBy对流是在水平方向上切分，window是对流在纵向上进行切分，如下图：

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h41wlx41fhj20tf0atgom.jpg)

从上图可以看出，我们将一个DataStream转换成AllWindowedStream虽然可以进行纵向上切分，但无法在多个实例上并行的对数据处理，为了能够在多个实例上并行对数据处理，我们可以先对
DataStream进行keyBy操作，然后在进行window划分，最终形成了我们的WindowedStream，WindowedStream的主要操作如下：


![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h41y1chcnqj20wv0qa4fd.jpg)


**WindowAssigner**

在DataStream中的window方法中需要传入WindowAssigner对象，WindowAssigner负责将每条数据分发到正确的window中（同一条数据可以被分发到多个Window中）。Flink中提供了如下的WindowAssigner：

- Tumbling Windows：窗口间的元素无重复
- Sliding Windows：窗口间元素可能重复
- Session Windows：窗口间的元素无重复
- Global Windows：全局的window，默认永远不触发窗口，需要自定义Trigger来触发窗口

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h41xl23af7j20jg0nbgr7.jpg)

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h41xlu8ztkj20j00m1tcm.jpg)

**Evictor**

在我们的WindowedStream中我们可以看到一个evictor方法，该方法主要用于做一些数据的自定义操作，可以在执行用户代码之前或者执行用户代码以后做一些操作，如下：
```java
public interface Evictor<T, W extends Window> extends Serializable {
    
    void evictBefore(
            Iterable<TimestampedValue<T>> elements,
            int size,
            W window,
            EvictorContext evictorContext);
    
    void evictAfter(
            Iterable<TimestampedValue<T>> elements,
            int size,
            W window,
            EvictorContext evictorContext);
    
}
```

在Flink中提供了几种通用的Evictor：

- CountEvictor：保留指定数量的元素
- DeltaEvictor：通过执行用户自定义的DeltaFunction和预设的threshold，判断是否删除一个元素
- TimeEvictor：设定一个阀值interval，删除窗口内小于最大时间戳(本窗口内)-interval的元素


**Trigger**

在我们的WindowedStream中我们可以看到一个trigger方法，该方法主要用来判断是一个窗口是否需要被触发，每个WindowsAssigner都自带一个默认的Trigger，Trigger的
定义如下：

```java
public abstract class Trigger<T, W extends Window> implements Serializable {
    public abstract TriggerResult onElement(T element, long timestamp, W window, TriggerContext ctx)
            throws Exception;
    
    public abstract TriggerResult onProcessingTime(long time, W window, TriggerContext ctx)
            throws Exception;
    
    public abstract TriggerResult onEventTime(long time, W window, TriggerContext ctx)
            throws Exception;
    
    public boolean canMerge() {
        return false;
    }
    
    public void onMerge(W window, OnMergeContext ctx) throws Exception {
        throw new UnsupportedOperationException("This trigger does not support merging.");
    }
    
    public abstract void clear(W window, TriggerContext ctx) throws Exception;
}
```

- onElement：每次往window增加一个元素的时候都会被触发
- onEventTime：当EventTime Timer被触发的时候调用
- onProcessingTime：当ProcessingTime Timer被触发的时候调用
- onMerge：对两个Trigger的State进行Merge操作
- clear()：window销毁的时候被调用

前三个方法都一个返回值TriggerResult，TriggerResult有以下几种选择：

- CONTINUE：什么事情都不做
- FIRE：触发window
- PURGE：清空整个window的元素并销毁窗口
- FIRE_AND_PURGE：触发窗口，然后销毁窗口

**Time和WaterMark**

之前我们已经说过在Flink中对Time进行了精细划分：

- EventTime：事件发生的时间
- ProcessingTime：处理消息的时间
- IngestionTime：进入Flink的时间

对于按照EventTime进行处理的应用程序，由于网络延迟或者其他原因，虽然EventTime是递增的，但是到达Flink的顺序确是不一定的，为了应对乱序问题我们引入了WaterMark。

当我们的WindowAssigner是基于EventTime的时候，我们需要设置WaterMark，通过assignTimestampsAndWatermarks方法我们可以产生WaterMark这个特殊事件，用来告诉Flink
某个时间戳以前的数据我都收到了，由于我们的WaterMark也只是一个估计值，因此及时设置了WaterMark，也有可能收到之前的数据(这些数据称为late elements)，因此Flink中提供
了两个方法来处理这些数据：

- allowedLateness：用于指定允许的延迟的最大时间，设置该时间以后，迟来的数据也可以触发窗口
- sideOutputLateData()：将迟到的数据发送到旁路输出流
- getSideOutput()：用于获取延迟数据并进行处理