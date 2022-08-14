package cn.sh.flink.learning.udf;

import cn.sh.flink.learning.utils.GeoUtils;
import org.apache.flink.table.functions.ScalarFunction;

public class IsInNYCFunction extends ScalarFunction {

    public IsInNYCFunction() {
    }

    public boolean eval(float lon, float lat) {
        return GeoUtils.isInNYC(lon, lat);
    }
}
