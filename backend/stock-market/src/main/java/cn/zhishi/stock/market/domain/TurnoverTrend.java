package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * MKT-04 响应体：市场成交量额趋势。
 *
 * <p>{@code points} 的语义由 {@code range} 决定，两者必须成对理解：
 * <ul>
 *   <li>盘中档位：每点是**从开盘累计到该时刻**的成交额与成交量（单调不减），
 *       {@code time} 为 ISO 8601 带时区偏移的分钟边界，{@code interval} 非空；</li>
 *   <li>跨日档位：每点是该交易日**全天**的成交额与成交量，
 *       {@code time} 为交易日 {@code yyyy-MM-dd}，{@code interval} 为 {@code null}。</li>
 * </ul>
 *
 * <p>{@code dataCutoffAt} 恒等于最后一个点位的时刻——趋势数据的可信边界就是它最后一个点，
 * 不另立一个可能与之矛盾的时间。
 */
public record TurnoverTrend(
        String marketCode,
        String range,
        String interval,
        Unit unit,
        OffsetDateTime dataCutoffAt,
        List<Point> points) {

    public TurnoverTrend {
        points = List.copyOf(points);
    }

    /**
     * 单位声明。
     *
     * <p>响应里同时有金额与数量两个量，单个字符串无法承载两种单位，因此按字段拆开声明。
     */
    public record Unit(String tradeAmount, String tradeVolume) {

        /** 与全局约定一致：金额为人民币元，成交量为股。 */
        public static Unit standard() {
            return new Unit("CNY", "SHARE");
        }
    }

    /** 单个趋势点。金额与数量按接口约定使用十进制定点数字符串，避免浮点误差。 */
    public record Point(String time, String tradeAmount, String tradeVolume) {
    }
}
