1. Flink时间语义
2. Timestamp分配和Watermark生成
3. Watermark传播
4. ProcessFunction
5. Watermark处理
6. Table指定时间列
7. 时间列操作

**Flink时间语义**

在Flink中主要以两种时间语义：

- ProcessingTime：处理数据的时间，处理简单，结果不确定无法重现
- EventTime：数据的产生时间，通常是根据数据所携带的时间戳来确定，处理复杂，结果确定可重现


在判断应该使用哪种类型的时间时可以遵循一个原则：当你的应用遇到某些问题需要从上一个checkpointy或者savepoint进行重放，如果希望结果完全相同就只能使用EventTime，如果接受结果不同，则可以用ProcessingTime。

对于ProcessingTime，单调递增，但对于EventTime来说，由于网络延迟、程序内部逻辑等原因可能会乱序到达，为了解决EventTime的乱序问题，我们可以将多条数据组成一小批次，这些批次之间的时间是单调递增的。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h590y2qcsxj20eu06vgme.jpg)

为了达到上述目的，我们通常会在整个时间序列插入一些类似标志位的特殊处理数据，该数据称之为watermark，watermark表示以后到来的数据已经不会再小于等于这个时间了。

**Timestamp分配和Watermark生成**

Watermark的生成方式大体有两种：

- SourceFunction：从数据源处进行Timestamp分配和Watermark生成，通过collectWithTimestamp发送一条数据，第一个参数为要发送的数据，第二个参数为数据对应的时间戳；使用emitWatermark去产生一条watermark，表示接下来不会有小于该时间戳的数据
- DataStreamAPI（流处理过程中指定）：适用于SourceFunction不支持生成timestamp和watermark，或者不想在Source处生成时，我们可以通过DataStream.assignTimestampsAndWatermarks方法处理，该方法可以接收不同的timestamp和watermark生成器。


Watermark生成器总体分为两类：

- 定期生成：时间驱动（定期调用生成逻辑去产生一个watermark）
- 数据驱动：当看到一些特殊的记录时表示接下来不会有符合条件的数据再发过来，每一次分配Timestamp以后都会调用用户实现的watermark生成方法，用户需要在生成方法中去实现watermark的生成逻辑


在生成watermark时，尽量选择靠近Source处，这样可以让更多的Operator去判断某些数据是否乱序，**Flink内部也提供了良好的机制保证timestamp和watermark被正确的传递到下游节点**。

**Watermark传播**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5brt1n4adj20un0c1jw7.jpg)

上图的蓝色块代表一个算子的一个任务，他有三个输入分别是W1、W2、W3（可以属于相同流，也可以属于不同流），在计算watermark时，对于单个输入取他们的最大值（因为watermark遵循单调递增的规则），但是如果多输入之间，需要统计多个输入之间的最小（即对于多输入的任务，wartermark受限于最慢的那条输入流）。

**watermark的传播是幂等的**。

watermark在传播时对于输入没有区分是一条流的partition还是多流的join，对于单流的partition强制时钟同步没有问题，但是对于join操作如果强制时钟同步有可能会将一个离现在很近的数据流和一个离当前很远的数据流进行join，此时对于快的数据流就必须要等待很远的数据流（也就是需要在状态中缓存非常多的数据），对于整个集群来说是个很大的消耗。

**ProcessFunction**

Watermark在任务里的处理逻辑分我内部逻辑和外部逻辑，外部逻辑是通过ProcessFunction来体现的，ProcessFunction提供了和时间相关主要有以下功能：

- 获取当前记录的Timestamp或者当前的Processing Time
- 获取当前算子的时间，可以理解为watermark
- 注册一些Timer(定时器)，用于实现一些相对复杂的功能。主要有三个方法：registerEventTimeTimer、registerProcessingTimeTimer和onTimer，在onTimer方法中需要实现自己的回调逻辑，当条件满足时该逻辑就会被触发。


![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5dfbeuncoj20si05cjux.jpg)

**Watermark处理**

算在的实例在收到watermark时，会先更新当前的算子时间，后续我们在ProcessFunction里面查询这个算子时间时就可以获取到最新时间。

接着它会遍历计时器队列（Timer），然后逐一触发用户的回调逻辑。

最后Flink会将当前的watermark发送到下游的其他任务实例上，从而完成整个wartmark的传播。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5dgluuwiaj20uq02sdh1.jpg)

**Table指定指定时间列**

为了让Table/SQL这一层可以使用时间，Flink采用的是将时间属性提前放到Schema里面。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5dgpyys0bj20ob0bojwo.jpg)

对于ProcessingTime，如果我们的Table是从DataStream转换而来，只需要在最后用"列名.proctime"来把最后一列（必须是最后一列）注册为一个Processing Time，如果是通过TableSource生成的，就可以通过实现DefinedProctimeAttribute接口，通过该接口我们就可以提供逻辑去生成对应的Processing Time。


对于EventTime，如果我们的Table是从DataStream转换而来，必须保证原始的DataStream里面已经存储了Record Timestamp和Watermark，只需要加上"列名.rowtime"就可以，如果是通过TableSource生成，实现DefinedRowtimeAttributes接口即可。

**时间列操作**

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h5djtg1tbbj20mn0c077n.jpg)

在时间列上我们可以进行如下操作：

- Over窗口聚合
- Group By窗口聚合
- 时间窗口聚合：只支持对应的时间列
- 排序：排序的时候时间列必须，且必须放在第一位
