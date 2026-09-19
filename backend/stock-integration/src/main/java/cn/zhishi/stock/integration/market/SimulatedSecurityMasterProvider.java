package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 确定性模拟证券主数据。
 *
 * <p>**不重复定义证券全集**，而是从 {@link SecurityQuoteProvider} 的全集投影出主数据：
 * 代码段一旦在两处各写一遍，改动其中一处就会让"主数据"与"广度计数"指向不同的证券全集，
 * 且没有任何测试会红。投影天然只有一份真相。
 *
 * <p>停牌状态取自**最近一个交易日**的行情快照（真实环境下该字段由主数据源直接提供）。
 * 模拟实现里目标状态只由证券序号决定、与日期无关，因此该取值是稳定的。
 *
 * <p>拼音字段恒为 {@code null}：合成名称（{@code 模拟证券600000}）没有可核实的拼音，
 * 与其编一份假拼音污染真实逻辑，不如留空——匹配能力本身已在
 * {@link cn.zhishi.stock.market.application.SecurityQueryService} 中保留并测试。
 */
public class SimulatedSecurityMasterProvider implements SecurityMasterProvider {

    private static final String SUPPORTED_MARKET = "CN";
    private static final String STATUS_LISTED = "LISTED";
    private static final String STATUS_SUSPENDED = "SUSPENDED";
    private static final int PRICE_SCALE = 2;

    private final SecurityQuoteProvider securityQuoteProvider;
    private final TradingCalendarProvider tradingCalendarProvider;
    private final Clock clock;

    public SimulatedSecurityMasterProvider(
            SecurityQuoteProvider securityQuoteProvider,
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        this.securityQuoteProvider = securityQuoteProvider;
        this.tradingCalendarProvider = tradingCalendarProvider;
        this.clock = clock;
    }

    @Override
    public List<SecuritySummary> findAll(String marketCode) {
        if (!SUPPORTED_MARKET.equals(marketCode)) {
            return List.of();
        }
        return securityQuoteProvider
                .fetchUniverse(marketCode, mostRecentTradingDay(marketCode))
                .stream()
                .map(SimulatedSecurityMasterProvider::toSummary)
                .toList();
    }

    /** 当日是交易日就用当日，否则退回上一交易日；日历不可用时退回今天。 */
    private LocalDate mostRecentTradingDay(String marketCode) {
        LocalDate today = LocalDate.now(clock);
        return tradingCalendarProvider.find(marketCode, today)
                .map(day -> day.tradingDay() ? day.tradeDate() : day.previousTradeDate())
                .orElse(today);
    }

    private static SecuritySummary toSummary(SecurityQuote quote) {
        return new SecuritySummary(
                quote.securityId(),
                quote.exchangeCode() + "." + quote.securityCode(),
                quote.securityCode(),
                quote.securityName(),
                quote.exchangeCode(),
                quote.securityType(),
                quote.boardCode(),
                quote.suspended() ? STATUS_SUSPENDED : STATUS_LISTED,
                quote.st(),
                quote.suspended(),
                PRICE_SCALE,
                null,
                null);
    }
}
