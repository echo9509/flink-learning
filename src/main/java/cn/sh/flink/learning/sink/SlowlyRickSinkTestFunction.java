package cn.sh.flink.learning.sink;

import cn.sh.flink.learning.sink.async.AbstractAsyncRichSinkFunction;

import java.util.Random;

/**
 * @author sh
 */
public class SlowlyRickSinkTestFunction extends AbstractAsyncRichSinkFunction<String> {

    @Override
    protected SinkTaskProcessor<String> getTaskProcessor() {
        return new SlowlySinkProcessor();
    }

    private static class SlowlySinkProcessor implements SinkTaskProcessor<String> {

        @Override
        public void process(String data) {
            try {
                Thread.sleep(new Random().nextInt(10));
                System.out.println(data);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
