package cn.sh.flink.learning.daemon.tableapi;

import org.apache.flink.table.api.*;

import java.util.Objects;

import static org.apache.flink.table.api.Expressions.$;

/**
 * @author sh
 */
public class JavaBatchWordCount {

    public static void main(String[] args) {
        EnvironmentSettings settings = EnvironmentSettings.newInstance().inBatchMode().build();
        TableEnvironment tableEnvironment = TableEnvironment.create(settings);
        String sourcePath = Objects.requireNonNull(JavaBatchWordCount.class.getClassLoader().getResource("words.csv")).getPath();
        Schema sourceSchema = Schema.newBuilder().column("word", DataTypes.STRING()).build();
        tableEnvironment.createTemporaryTable("sourceTable", TableDescriptor.forConnector("filesystem")
                .schema(sourceSchema)
                .option("path", sourcePath)
                .format("csv")
                .build());

        Table table = tableEnvironment.from("sourceTable").groupBy($("word")).select($("word"), $("word").count().as("count"));

        // 解释表，获取查询计划
        System.out.println(table.explain());
        // 执行查询，并打印结果
        table.execute().print();
        // 输出表
        tableEnvironment.createTable("CsvSinkTable", TableDescriptor.forConnector("filesystem")
                .option("path", "CsvSinkTableResult")
                .schema(Schema.newBuilder().column("word", DataTypes.STRING()).column("count", DataTypes.BIGINT()).build())
                .format(FormatDescriptor.forFormat("csv").build())
                .build());
        table.executeInsert("CsvSinkTable");
    }

}
