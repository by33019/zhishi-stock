package cn.zhishi.stock.market.domain;

import java.time.LocalDate;

/**
 * 单个 K 线点，字段与 {@code RESTful-API.md} §8.2 逐一对齐。
 *
 * <p>价格与量额一律用十进制定点数字符串：前端不做浮点运算，
 * 字符串是唯一能保证"展示值 == 服务端值"的载体（同 {@code MarketOverview.QuoteRow}、
 * {@code TurnoverTrend.Point}）。
 *
 * <p>{@code time} 是交易日。周 K / 月 K 取该周期**最后一个交易日**——
 * 与 M2-03「点位取区间结束时刻」同一原则，这样 {@code dataCutoffAt}
 * 天然等于最后一个点的时刻，不会出现"数据截止到 9/19、最后一点却标着 9/15"的矛盾。
 */
public record KlinePoint(
        LocalDate time,
        String openPrice,
        String highPrice,
        String lowPrice,
        String closePrice,
        String previousClosePrice,
        String changeAmount,
        String changeRate,
        String tradeVolume,
        String tradeAmount,
        String turnoverRate,
        KlineQualityStatus qualityStatus) {
}
