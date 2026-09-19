package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 确定性模拟个股快照（STK-04）。
 *
 * <p>证券身份**投影**自 {@link SecurityMasterProvider}，行情取自 {@link SecurityQuoteProvider}，
 * 开高低与量额由 {@link SimulatedPriceSeries} 派生——三者都不重新生成，
 * 因此"个股快照 ↔ 市场广度 ↔ 证券主数据"对同一只证券给出同一套事实。
 *
 * <p>停牌证券的最新价等于前收价（行情源就是这么给的），**不会被置零**。
 */
public class SimulatedQuoteSnapshotProvider implements QuoteSnapshotProvider {

  private static final String SEQUENCE_PREFIX = "sim-";

  private final SimulatedMarketAccess marketAccess;
  private final LimitRuleProvider limitRuleProvider;
  private final Clock clock;

  public SimulatedQuoteSnapshotProvider(
      SecurityQuoteProvider securityQuoteProvider,
      SecurityMasterProvider securityMasterProvider,
      LimitRuleProvider limitRuleProvider,
      TradingCalendarProvider tradingCalendarProvider,
      Clock clock) {
    this.marketAccess = new SimulatedMarketAccess(
        securityQuoteProvider, securityMasterProvider, tradingCalendarProvider, clock);
    this.limitRuleProvider = limitRuleProvider;
    this.clock = clock;
  }

  @Override
  public Optional<QuoteSnapshot> fetch(String securityId, String marketCode) {
    if (securityId == null || !marketAccess.supports(marketCode)) {
      return Optional.empty();
    }
    LocalDate tradeDate = marketAccess.latestTradeDate();
    Optional<SecurityQuote> quote = marketAccess.quote(securityId, tradeDate);
    Optional<SecuritySummary> summary = marketAccess.summary(securityId);
    if (quote.isEmpty() || summary.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(snapshot(summary.get(), quote.get(), tradeDate));
  }

  private QuoteSnapshot snapshot(
      SecuritySummary summary, SecurityQuote quote, LocalDate tradeDate) {
    BigDecimal previousClose = quote.previousClosePrice();
    BigDecimal latest = quote.latestPrice();
    LimitRule rule = LimitRuleMatcher
        .match(
            limitRuleProvider.rules(SimulatedMarketAccess.marketCode(), tradeDate),
            quote,
            tradeDate)
        .orElse(null);
    // 规则缺失时用"前收与最新价之间"作为价格边界，保证 high/low 仍然自洽
    BigDecimal limitUp = rule == null ? latest.max(previousClose) : rule.limitUpPrice(previousClose);
    BigDecimal limitDown =
        rule == null ? latest.min(previousClose) : rule.limitDownPrice(previousClose);

    SimulatedPriceSeries.DailyBar bar = SimulatedPriceSeries.bar(
        quote.securityId(), tradeDate, previousClose, latest, limitUp, limitDown);
    BigDecimal changeAmount = latest.subtract(previousClose);
    return new QuoteSnapshot(
        summary,
        SimulatedPriceSeries.formatPrice(previousClose),
        SimulatedPriceSeries.formatPrice(bar.openPrice()),
        SimulatedPriceSeries.formatPrice(latest),
        SimulatedPriceSeries.formatPrice(bar.highPrice()),
        SimulatedPriceSeries.formatPrice(bar.lowPrice()),
        SimulatedPriceSeries.formatPrice(changeAmount),
        SimulatedPriceSeries.formatChangeRate(changeAmount, previousClose),
        Long.toString(bar.tradeVolume()),
        SimulatedPriceSeries.formatPrice(bar.tradeAmount()),
        SimulatedPriceSeries.formatPrice(bar.turnoverRate()),
        marketAccess.sessionEndAt(tradeDate),
        OffsetDateTime.now(clock),
        SEQUENCE_PREFIX + tradeDate,
        MarketOverview.DataStatus.REALTIME,
        null);
  }
}
