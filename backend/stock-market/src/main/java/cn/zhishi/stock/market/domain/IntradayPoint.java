package cn.zhishi.stock.market.domain;

import java.time.LocalDateTime;

/**
 * 分时图的一个已闭合区间点（STK-06）。
 *
 * <p>价格是十进制定点字符串（"0.10" 即 10% 的同一条口径：不做浮点运算）；
 * 成交量单位股、成交额单位元。{@code time} 是区间**起点**的本地时间
 * （Asia/Shanghai），跨午休的两个时段各自独立分桶。
 */
public record IntradayPoint(
        LocalDateTime time,
        String openPrice,
        String highPrice,
        String lowPrice,
        String closePrice,
        String tradeVolume,
        String tradeAmount) {
}
