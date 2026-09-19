package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性模拟个股快照（STK-04）与整批快照（QTE-01 的取数基础）。
 *
 * <p>证券身份**投影**自 {@link SecurityMasterProvider}，行情取自 {@link SecurityQuoteProvider}，
 * 开高低与量额由 {@link SimulatedPriceSeries} 派生——三者都不重新生成，
 * 因此"个股快照 ↔ 整批快照 ↔ 市场广度 ↔ 证券主数据"对同一只证券给出同一套事实。
 *
 * <p>单只查询与整批查询**共用同一个装配方法** {@link #snapshot}，两条路径的差别只在该取哪几只。
 * 若各写一遍，改一处就会让榜单与个股页对同一只证券给出不同价格，且不会有任何测试变红。
 *
 * <p>停牌证券的最新价等于前收价（行情源就是这么给的），**不会被置零**。
 */
public class SimulatedQuoteSnapshotProvider
    implements QuoteSnapshotProvider, QuoteSnapshotBatchProvider {

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
    return Optional.of(snapshot(summary.get(), quote.get(), tradeDate, rules(tradeDate)));
  }

  /**
   * 返回整批快照。
   *
   * <p>行情与限幅规则各只取一次，主数据一次性建索引——逐只查询会让 5149 只证券的装配
   * 退化成 2600 万次比较。缺主数据的行情被跳过：宁可在榜单里少一行，
   * 也不要一行没有身份的行情。
   */
  @Override
  public List<QuoteSnapshot> fetchBatch(String marketCode) {
    if (!marketAccess.supports(marketCode)) {
      return List.of();
    }
    LocalDate tradeDate = marketAccess.latestTradeDate();
    List<LimitRule> rules = rules(tradeDate);
    Map<String, SecuritySummary> summaries = marketAccess.summaries();
    List<QuoteSnapshot> snapshots = new ArrayList<>();
    for (SecurityQuote quote : marketAccess.universe(tradeDate)) {
      SecuritySummary summary = summaries.get(quote.securityId());
      if (summary == null) {
        continue;
      }
      snapshots.add(snapshot(summary, quote, tradeDate, rules));
    }
    return List.copyOf(snapshots);
  }

  /** 单只与整批共用的装配：这一只证券在这一天的快照长什么样，只有这一处定义。 */
  private QuoteSnapshot snapshot(
      SecuritySummary summary, SecurityQuote quote, LocalDate tradeDate, List<LimitRule> rules) {
    BigDecimal previousClose = quote.previousClosePrice();
    BigDecimal latest = quote.latestPrice();
    LimitRule rule = LimitRuleMatcher.match(rules, quote, tradeDate).orElse(null);
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

  private List<LimitRule> rules(LocalDate tradeDate) {
    return limitRuleProvider.rules(SimulatedMarketAccess.marketCode(), tradeDate);
  }
}
