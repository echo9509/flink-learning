package cn.sh.flink.learning.sink.async;

import cn.sh.flink.learning.sink.async.AbstractAsyncRichSinkFunction.SinkTaskProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class AsyncSinkTaskRunnable<T> implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncSinkTaskRunnable.class);

    private static final long DEFAULT_POLL_TIMEOUT_MS = 500L;

    /** 数据缓存队列 */
    private final LinkedBlockingQueue<T> bufferQueue;

    /** 任务处理器，用于处理缓队列的数据 */
    private final SinkTaskProcessor<T> processor;

    /** Checkpoint栅栏计数器 */
    private final CyclicBarrier checkpointBarrier;

    /** 从数据缓队列中拉取超时时间 */
    private final long pollTimeoutMs;

    public AsyncSinkTaskRunnable(
            LinkedBlockingQueue<T> bufferQueue,
            SinkTaskProcessor<T> processor,
            CyclicBarrier checkpointBarrier) {
        this(bufferQueue, processor, checkpointBarrier, DEFAULT_POLL_TIMEOUT_MS);
    }

    public AsyncSinkTaskRunnable(
            LinkedBlockingQueue<T> bufferQueue,
            SinkTaskProcessor<T> processor,
            CyclicBarrier checkpointBarrier,
            long pollTimeoutMs) {
        this.bufferQueue = bufferQueue;
        this.processor = processor;
        this.checkpointBarrier = checkpointBarrier;
        this.pollTimeoutMs = pollTimeoutMs;
    }

    @Override
    public void run() {
        T data;
        while (true) {
            try {
                data = bufferQueue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS);
                if (Objects.isNull(data) && checkpointBarrier.getNumberWaiting() > 0) {
                    checkpointBarrier.await();
                    continue;
                }
                processor.process(data);
            } catch (InterruptedException | BrokenBarrierException e) {
                LOGGER.error("async sink task process data failed.", e);
            }
        }
    }
}
