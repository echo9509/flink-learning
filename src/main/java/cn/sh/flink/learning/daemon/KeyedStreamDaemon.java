package cn.sh.flink.learning.daemon;

import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;


/**
 * @author sh
 */
public class KeyedStreamDaemon {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<Order> source = env.fromElements(ORDERS);
        source.keyBy(Order::getProductName)
                .maxBy("price", true)
                .addSink(new SinkFunction<Order>() {
            @Override
            public void invoke(Order order, Context context) throws Exception {
                System.out.println("产品名称：" + order.getProductName() + "，购买价格：" + order.getPrice() + "，倒霉蛋：" + order.getCustomerName());
            }
        });
        env.execute("Keyed Stream Daemon");
    }

    private static final Order[] ORDERS = new Order[] {
            new Order("MacBook Pro", 100, "小明"),
            new Order("MacBook Pro", 100, "小红"),
            new Order("iphone pro max", 50, "小明"),
            new Order("iphone pro max", 30, "小红"),
            new Order("iphone pro max", 40, "小明")
    };

    public static class Order {

        private String productName;
        private Integer price;
        private String customerName;

        public Order() {
        }

        public Order(String productName, Integer price, String customerName) {
            this.productName = productName;
            this.price = price;
            this.customerName = customerName;
        }

        public String getProductName() {
            return productName;
        }

        public Integer getPrice() {
            return price;
        }

        public String getCustomerName() {
            return customerName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public void setPrice(Integer price) {
            this.price = price;
        }

        public void setCustomerName(String customerName) {
            this.customerName = customerName;
        }
    }


}
