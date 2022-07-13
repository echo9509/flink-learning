package cn.sh.flink.learning.daemon;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.windowing.delta.DeltaFunction;
import org.apache.flink.streaming.api.windowing.assigners.GlobalWindows;
import org.apache.flink.streaming.api.windowing.evictors.TimeEvictor;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.DeltaTrigger;

import java.util.concurrent.TimeUnit;


public class WindowedStreamDaemon {

    /**
     * 下面的的代码有两辆车，统计1个时间窗口每辆车的最大速度
     * 每辆车每100ms做一次速度改变（数据源）
     *
     * 在过去10s内行驶超过50米时，触发窗口计算查看这段时间内最大的车速
     *
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Tuple4<Integer, Integer, Double, Long>> carData = env.addSource(CarSource.create(2)).name("in-memory-source");

        int evictionSec = 10;
        double triggerMeters = 50;
        DataStream<Tuple4<Integer, Integer, Double, Long>> topSpeeds =
                carData.assignTimestampsAndWatermarks(
                        WatermarkStrategy
                                .<Tuple4<Integer, Integer, Double, Long>>
                                        forMonotonousTimestamps()
                                .withTimestampAssigner((car, ts) -> car.f3))
                        .keyBy(value -> value.f0)
                        .window(GlobalWindows.create())
                        .evictor(TimeEvictor.of(Time.of(evictionSec, TimeUnit.MILLISECONDS)))
                        .trigger(
                                DeltaTrigger.of(
                                        triggerMeters,
                                        new DeltaFunction<
                                                                                        Tuple4<Integer, Integer, Double, Long>>() {
                                            private static final long serialVersionUID = 1L;

                                            @Override
                                            public double getDelta(
                                                    Tuple4<Integer, Integer, Double, Long>
                                                            oldDataPoint,
                                                    Tuple4<Integer, Integer, Double, Long>
                                                            newDataPoint) {
                                                return newDataPoint.f2 - oldDataPoint.f2;
                                            }
                                        },
                                        carData.getType().createSerializer(env.getConfig())))
                        .maxBy(1);
        topSpeeds.addSink(new RichSinkFunction<Tuple4<Integer, Integer, Double, Long>>() {
            @Override
            public void invoke(Tuple4<Integer, Integer, Double, Long> value, Context context) throws Exception {
                System.out.println("current timestamp:" + System.currentTimeMillis() + ", " + value.toString());
            }
        });

        env.execute("CarTopSpeedWindowing Daemon");
    }
}
