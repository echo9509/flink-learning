package cn.sh.flink.learning.daemon;

import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.RichCoMapFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;

public class ConnectStreamDaemon {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<Order> source = env.fromElements(ORDERS);
        DataStreamSource<Order> source1 = env.fromElements(VIP_ORDERS);
        source.connect(source1).map(new DiscountCoMapFunction(0.8)).addSink(new SinkFunction<Order>() {
            @Override
            public void invoke(Order order, Context context) throws Exception {
                System.out.println("产品名称：" + order.getProductName() + "，应付：" + order.getPrice() + "，实付：" + order.getActualPrice() + ", 客户：" + order.getCustomerName());
            }
        });
        env.execute("Connect Stream Daemon");
    }

    private static class DiscountCoMapFunction extends RichCoMapFunction<Order, Order, Order> {

        private double discount;

        public DiscountCoMapFunction(double discount) {
            this.discount = discount;
        }

        @Override
        public Order map1(Order value) throws Exception {
            value.setActualPrice(value.getPrice() * discount);
            return value;
        }

        @Override
        public Order map2(Order value) throws Exception {
            // VIP在原有的优惠上再打7折
            value.setActualPrice(value.getPrice() * discount * 0.7);
            return value;
        }

    }

    private static final Order[] ORDERS = new Order[]{
            new Order("MacBook Pro", 100, "小明"),
            new Order("iphone pro max", 40, "小明")
    };

    private static final Order[] VIP_ORDERS = new Order[]{
            new Order("MacBook Pro", 100, "小红"),
            new Order("iphone pro max", 30, "小红")
    };

    public static class Order {

        private String productName;
        private Integer price;
        private String customerName;
        private double actualPrice;

        public Order() {
        }

        public Order(String productName, Integer price, String customerName) {
            this.productName = productName;
            this.price = price;
            this.customerName = customerName;
        }

        public double getActualPrice() {
            return actualPrice;
        }

        public void setActualPrice(double actualPrice) {
            this.actualPrice = actualPrice;
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
