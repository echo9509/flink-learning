1. 什么是流处理
2. 流处理的基本模型
3. Tuple
4. FlatMap
5. Function
6. RickFunction
7. WordCount实现
8. DataStream

**什么是流处理？**

流处理就是我们对流动的数据（无限的数据）进行处理，通常我们会提前设置好算子（也就是你的处理逻辑），当数据到达后对数据进行处理。

**流处理的模型什么？**

为了表达我们复杂的计算逻辑，Flink使用DAG图来表达整个计算逻辑，DAG的每一个点都代表一个基本的逻辑计算单元（算子），数据会按照DAG图的边进行流动，从数据源出发，
流经算子，最后通过Sink节点将结果输出到外部系统。

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h40nzb090rj20gb07w76f.jpg)

如上图所示，DAG图只是简单的逻辑图，不包含并发（也就是实际的执行情况），在实际执行的时候，每个算子可能被分配到多个实例上，对于同一个实例的上下游算子可以不需要网络，
但是如果上下游算子不在同一个实例上则需要通过网络进行数据传输，如下图：

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h40o3q3mlmj20ft0antb9.jpg)

**Tuple**

Tuple在Flink中我们经常使用的一个类，Tuple用来存放固定个数的属性，最多支持存放25个属性，

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h40p8c7pe3j21bt09sjue.jpg)

从上图可以看出，Tuple有很多个实现类，Tuple2代表可以存放2个属性，Tuple25代表可以存放25个属性。

**FlatMap**

FlatMap的作用是输入一个元素，输出多个元素，DataStream.flatMap方法通常需要传入一个FlatMapFunction，该函数中有一个flatMap方法用于将一个元素转换为多个元素，

```java
public interface FlatMapFunction<T, O> extends Function, Serializable {
    void flatMap(T value, Collector<O> out) throws Exception;
}
```

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h40qbbd2x8j20nc0do76u.jpg)

从类图上我们可以看到我们FlatMapFunction继承自Function，同时他有一个子类抽象类RichFlatMapFunction，RichFlatMapFunction又继承自AbstractRichFunction，AbstractRichFunction
又实现了RickFunction，RickFunction最终又继承了Function。

**Function**

Function是Flink中我们所有自定义操作的接口（包括Flink自己实现的一些操作），该接口没有任何方法，只是用来声明实现该接口的类可以用做算子的处理逻辑。

```java
public interface Function extends java.io.Serializable {}
```

**RickFunction**

RickFunction在Function的声明外，加入了一些方法，如下：

```java
@Public
public interface RichFunction extends Function {
    void open(Configuration parameters) throws Exception;
    
    void close() throws Exception;

    RuntimeContext getRuntimeContext();
    
    IterationRuntimeContext getIterationRuntimeContext();
    
    void setRuntimeContext(RuntimeContext t);
}
```

- open：可以分别在算子开始执行运算逻辑（比如map、flatmap、reduce等）前进行一些资源的初始化、
- close：可以分别在算子运算完成以后进行一些资源的释放
- RuntimeContext：提供了函数运行时的一些上下文信息，比如函数执行时的并行度，任务名字以及State状态

**WordCount**

我们的WordCount程序主要分为4个步骤：

1. 步骤1是获取Flink的执行环境
2. 步骤2是添加数据源
3. 步骤3主要是构造我们的运算逻辑，通过步骤2和3，我们的逻辑DAG图就构造完毕，也就是说我们的运算逻辑就确定了。
4. 提交我们的任务，只有执行了步骤4，我们前面的逻辑才会真正执行。

关于运算逻辑：

1. 数据源来自于我们定义好的数组（数组中的每一项都是一句话，包含若干个单词），数据从数据源出发后，首先会经历我们FlatMap算子的处理，具体怎么处理由我们自定义Tokenizer中的flatMap方法实现，
可以看到我们会对数据按照正则切分成若干个单词，对于每一个单词我们都会形成一个Tuple2，第一个属性代表单词，第二个属性就是1，并将其加入到我们的Collector中
2. 当数据经过FlatMap算子的处理后，我们下面需要对每个单词进行分组，在这里我们需要自定义KeySelector中的getKey方法，这里表示我们需用根据什么分组，由于我们是根据单词分组，单词被我们存储在
了Tuple2的第1个属性中（也就是f0），因此我们这里直接返回了value.f0，经过分组后流就变成了KeyedStream
3. 当流变为KeyedStream以后，我们才可以进行sum操作，sum这里的参数可以填写属性的位置，也可以填写属性的名称，由于我们是对Tuple中第二个属性进行求和（因此这里可以写1或者字符串f1）
4. 经过求和以后我们就可以对结果进行输出了，通过addSink我们可以添加一个输出，addSink方法中需要实现一个SinkFunction，我们自定义的Function就是对单词和单词出现的次数进行打印。

```java
public class WordCount {

    public static void main(String[] args) throws Exception {
        // 步骤1
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 步骤2
        DataStreamSource<String> source = env.fromElements(WORDS);
        // 步骤3
        source.flatMap(new Tokenizer()).keyBy(new KeySelector<Tuple2<String, Integer>, Object>() {

            @Override
            public Object getKey(Tuple2<String, Integer> value) throws Exception {
                return value.f0;
            }
        }).sum(1).addSink(new SinkFunction<Tuple2<String, Integer>>() {
            @Override
            public void invoke(Tuple2<String, Integer> value, Context context) throws Exception {
                System.out.println("单词：" + value.f0 + "，数量:" + value.f1);
            }
        });
        // 步骤4
        env.execute("WordCount Stream");
    }

    private static class Tokenizer extends RichFlatMapFunction<String, Tuple2<String, Integer>> {

        @Override
        public void flatMap(String value, Collector<Tuple2<String, Integer>> out) {
            String[] tokens = value.toLowerCase().split("\\W+");
            for (String token : tokens) {
                if (token.length() > 0) {
                    out.collect(new Tuple2<>(token, 1));
                }
            }
        }
    }

    public static final String[] WORDS =
            new String[] {
                    "To be, or not to be,--that is the question:--",
                    "Whether 'tis nobler in the mind to suffer",
                    "The slings and arrows of outrageous fortune",
                    "Or to take arms against a sea of troubles,",
                    "And by opposing end them?--To die,--to sleep,--",
                    "No more; and by a sleep to say we end",
                    "The heartache, and the thousand natural shocks",
                    "That flesh is heir to,--'tis a consummation",
                    "Devoutly to be wish'd. To die,--to sleep;--",
                    "To sleep! perchance to dream:--ay, there's the rub;",
                    "For in that sleep of death what dreams may come,",
                    "When we have shuffled off this mortal coil,",
                    "Must give us pause: there's the respect",
                    "That makes calamity of so long life;",
                    "For who would bear the whips and scorns of time,",
                    "The oppressor's wrong, the proud man's contumely,",
                    "The pangs of despis'd love, the law's delay,",
                    "The insolence of office, and the spurns",
                    "That patient merit of the unworthy takes,",
                    "When he himself might his quietus make",
                    "With a bare bodkin? who would these fardels bear,",
                    "To grunt and sweat under a weary life,",
                    "But that the dread of something after death,--",
                    "The undiscover'd country, from whose bourn",
                    "No traveller returns,--puzzles the will,",
                    "And makes us rather bear those ills we have",
                    "Than fly to others that we know not of?",
                    "Thus conscience does make cowards of us all;",
                    "And thus the native hue of resolution",
                    "Is sicklied o'er with the pale cast of thought;",
                    "And enterprises of great pith and moment,",
                    "With this regard, their currents turn awry,",
                    "And lose the name of action.--Soft you now!",
                    "The fair Ophelia!--Nymph, in thy orisons",
                    "Be all my sins remember'd."
            };
}
```

**DataStream**

从上面的使用可以看出，Flink DataStream AP整个的核心就是代表流数据的DataStream对象，我们整个逻辑运算都是围绕DataStream对象进行操作然后产生新的DataStream对象，对于DataStream
单条记录我们可以进行filter、map等操作，或者基于window对多条记录进行操作，同时我们也可以将单条流（DataStream）进行拆分，也可以对多条流进行合并，如下图：

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h40tknnvn6j20vv086gp8.jpg)

在Flink中，最基础的流是DataStream，但是经过上面的操作以后可能会产生各种各样的流类型，目前Flink中的流的转换关系图如下：

![image.png](https://tva1.sinaimg.cn/large/69ad3470gy1h40tn4gxkuj20s10dutdw.jpg)

在上面各式各样的流中，每个流都有自己独特的特点及操作，同时多种流之间也支持某些通用的操作，关于每种流的使用我们下篇进行详细实践。



