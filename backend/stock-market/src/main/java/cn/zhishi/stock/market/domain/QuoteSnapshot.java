package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;

/**
 * 个股行情快照（STK-04 响应体），字段与 {@code RESTful-API.md} §4.2 逐一对齐。
 *
 * <p>与 {@link SecurityQuote} 的分工是刻意的：{@code SecurityQuote} 是市场广度计数的输入单位，
 * 只需要前收价与最新价就能分类涨跌停；本模型是**对外展示**的完整快照，
 * 含开高低、量额、换手率与数据时效字段。两者不合并——
 * 把展示字段塞进计数输入，会让每个计数消费方都要重新判断"哪些字段在这里有意义"。
 *
 * <p>价格与量额用十进制定点数字符串，对应 §4.2 的 {@code decimal-string} / {@code integer-string}。
 *
 * <p>停牌证券返回最近有效价格，{@code security.isSuspended = true}，
 * **不得将价格置零**（§8.3）。
 */
public record QuoteSnapshot(
        SecuritySummary security,
        String previousClosePrice,
        String openPrice,
        String latestPrice,
        String highPrice,
        String lowPrice,
        String changeAmount,
        String changeRate,
        String tradeVolume,
        String tradeAmount,
        String turnoverRate,
        OffsetDateTime dataTime,
        OffsetDateTime serverTime,
        String sequence,
        MarketOverview.DataStatus dataStatus,
        Integer delaySeconds) {
}
