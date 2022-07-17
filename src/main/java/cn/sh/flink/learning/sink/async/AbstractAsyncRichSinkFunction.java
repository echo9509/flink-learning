package cn.sh.flink.learning.sink.async;

import com.sun.istack.internal.NotNull;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.*;

public abstract class AbstractAsyncRichSinkFunction<T> extends RichSinkFunction<T>
        implements CheckpointedFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractAsyncRichSinkFunction.class);

    private static final int DEFAULT_CLIENT_THREAD_NUM = 10;
    private static final int DEFAULT_QUEUE_CAPACITY = 500;
    private static final String THREAD_NAME = "AsyncSinkTask";

    /** 处理数据线程的数量 */
    private final int threadNum;
    /** 存放任务队列的最大数量 */
    private final int bufferQueueCapacity;

    private LinkedBlockingQueue<T> bufferQueue;
    private CyclicBarrier checkpointBarrier;

    public AbstractAsyncRichSinkFunction() {
        this(DEFAULT_CLIENT_THREAD_NUM, DEFAULT_QUEUE_CAPACITY);
    }

    public AbstractAsyncRichSinkFunction(int threadNum, int bufferQueueCapacity) {
        this.threadNum = threadNum;
        this.bufferQueueCapacity = bufferQueueCapacity;
    }

    @Override
    public final void open(Configuration parameters) throws Exception {
        super.open(parameters);
        ThreadPoolExecutor threadPoolExecutor =
                new ThreadPoolExecutor(
                        threadNum,
                        threadNum,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(),
                        new SinkThreadFactory(THREAD_NAME));
        this.bufferQueue = new LinkedBlockingQueue<>(bufferQueueCapacity);
        this.checkpointBarrier = new CyclicBarrier(threadNum + 1);
        AsyncSinkTaskRunnable<T> runnable =
                new AsyncSinkTaskRunnable<>(bufferQueue, getTaskProcessor(), checkpointBarrier);
        for (int i = 0; i < threadNum; i++) {
            threadPoolExecutor.execute(runnable);
        }
    }

    protected abstract SinkTaskProcessor<T> getTaskProcessor();

    @Override
    public final void invoke(T value, Context context) throws Exception {
        if (Objects.isNull(value)) {
            return;
        }
        bufferQueue.put(value);
    }

    @Override
    public final void snapshotState(FunctionSnapshotContext context) throws Exception {
        long start = System.currentTimeMillis();
        LOGGER.info("checkpoint prepare, wait sink thread flush data, current timestamp:{}", start);
        checkpointBarrier.await();
        long end = System.currentTimeMillis();
        LOGGER.info(
                "checkpoint can start, sink thread flush data completed, current timestamp:{}, flush cost ms:{}",
                end,
                (end - start));
    }

    @Override
    public final void initializeState(FunctionInitializationContext context) throws Exception {}

    private static class SinkThreadFactory implements ThreadFactory {

        private static final String THREAD_NAME_FORMAT = "%s-Thread-%d";

        /** 线程池线程的数量 */
        private int count;

        /** 线程前缀名称 */
        private final String name;

        public SinkThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r, String.format(THREAD_NAME_FORMAT, name, count));
            count++;
            return thread;
        }
    }

    public interface SinkTaskProcessor<T> extends Serializable {

        /**
        * 处理任务
        *
        * @param data
        */
        void process(T data);
    }
}
