package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;

/**
 * 板块行情统计，是 {@code RESTful-API.md} §10 SEC-02 的 `items[]` 行、
 * 同时也是 SEC-03 / SEC-04 的核心内容。
 *
 * <p>一份记录服务三个接口：三个接口的差别只在"是否附带板块主数据与父级"、
 * "是分页还是单条"，统计字段本身必须完全一致。拆成两个记录会让
 * "均价算不算停牌股"这类口径在两条链路上各说各话。
 *
 * <p>{@code averagePrice} / {@code changeRate} 可空：板块全部成分股停牌时
 * "平均涨跌幅"没有定义，补 {@code 0} 会被读成"板块平盘"
 * （PRD §7.4 SEC-02 明确「历史断点不得补 0」）。
 *
 * <p>{@code companyCount} 是**成分事实**（含停牌），与
 * {@code averagePrice} / {@code changeRate} 的样本集（不含停牌）不同，
 * 这一点写在 {@link SectorQuoteCalculator} 的口径里，前端不得用前者反推后者。
 */
public record SectorQuote(
        String sectorId,
        String sectorCode,
        String sectorName,
        String sectorType,
        int companyCount,
        String averagePrice,
        String changeRate,
        String tradeVolume,
        String tradeAmount,
        SectorLeaderStock leadingStock,
        SectorLeaderStock laggingStock,
        OffsetDateTime dataTime,
        MarketOverview.DataStatus dataStatus) {
}
