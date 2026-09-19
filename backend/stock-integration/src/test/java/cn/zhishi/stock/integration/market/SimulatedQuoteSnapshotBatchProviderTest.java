package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.application.RankingCriteria;
import cn.zhishi.stock.market.application.StockRankingQueryService;
import cn.zhishi.stock.market.domain.BreadthCalculator;
import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.StockRanking;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 整批快照路径（QTE-01 的取数基础）。
 *
 * <p>这一组测试的作用不是"验证批量方法能跑"，而是**钉死跨来源一致性**：
 * 同一只证券的价格在"整批快照""单只查询""市场广度计数"三处必须给出同一个答案。
 * 这三条路径各写一遍生成逻辑的话，改一处就会让它们悄悄分叉，而且不会有任何测试变红。
 */
class SimulatedQuoteSnapshotBatchProviderTest {

  /** 2026-09-18（周五）15:00 北京时间。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-18T07:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 18);

  private static final String MARKET = "CN";

  private final LimitRuleProvider limitRules = new SimulatedLimitRuleProvider();

  private final TradingCalendarProvider calendar =
      SimulatedTradingCalendarProvider.ofCsv(CLOCK, "");

  private final SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(limitRules);

  private final SecurityMasterProvider master =
      new SimulatedSecurityMasterProvider(quotes, calendar, CLOCK);

  private final SimulatedQuoteSnapshotProvider provider =
      new SimulatedQuoteSnapshotProvider(quotes, master, limitRules, calendar, CLOCK);

  // ---------- 批次形状 ----------

  @Test
  void returnsWholeUniverseInOneBatch() {
    assertThat(provider.fetchBatch(MARKET))
        .hasSameSizeAs(quotes.fetchUniverse(MARKET, TRADE_DATE))
        .isNotEmpty();
  }

  @Test
  void returnsEmptyForUnsupportedMarket() {
    assertThat(provider.fetchBatch("US")).isEmpty();
  }

  /** 批次里不能出现主数据查不到的证券——否则榜单会渲染出点不进去的幽灵行。 */
  @Test
  void onlyContainsSecuritiesPresentInMasterData() {
    List<String> knownIds = master.findAll(MARKET).stream()
        .map(summary -> summary.securityId())
        .toList();

    assertThat(provider.fetchBatch(MARKET)).allSatisfy(snapshot ->
        assertThat(knownIds).contains(snapshot.security().securityId()));
  }

  // ---------- 跨来源一致性 ----------

  /** 整批路径与单只查询必须给出**同一个对象**：两者共用同一套装配，差异只在该取哪几只。 */
  @Test
  void batchRowEqualsSingleLookup() {
    Map<String, QuoteSnapshot> batch = snapshotsById(provider.fetchBatch(MARKET));

    for (String securityId : List.of("sim-600000", "sim-300001", "sim-430001")) {
      assertThat(batch.get(securityId))
          .isEqualTo(provider.fetch(securityId, MARKET).orElseThrow());
    }
  }

  @Test
  void mirrorsLatestPriceFromQuoteUniverseForEverySecurity() {
    Map<String, BigDecimal> latestBySecurity = quotes.fetchUniverse(MARKET, TRADE_DATE).stream()
        .collect(Collectors.toMap(SecurityQuote::securityId, SecurityQuote::latestPrice));

    assertThat(provider.fetchBatch(MARKET)).allSatisfy(snapshot ->
        assertThat(new BigDecimal(snapshot.latestPrice()))
            .isEqualByComparingTo(latestBySecurity.get(snapshot.security().securityId())));
  }

  /**
   * 批次里的涨跌方向必须与市场广度计数逐项相同。
   *
   * <p>两者都从同一批行情出发，但一条走"逐只装配成快照"，另一条走"直接计数"。
   * 这条断言让"榜单说的涨"与"首页说的涨跌家数"不可能各说各话。
   */
  @Test
  void agreesWithBreadthClassification() {
    MarketOverview.BreadthData breadth = BreadthCalculator.calculate(
        quotes.fetchUniverse(MARKET, TRADE_DATE), limitRules.rules(MARKET, TRADE_DATE), TRADE_DATE);
    List<QuoteSnapshot> batch = provider.fetchBatch(MARKET);

    assertThat(count(batch, snapshot -> snapshot.security().isSuspended()))
        .isEqualTo(breadth.suspendedCount());
    assertThat(count(batch, snapshot -> !snapshot.security().isSuspended() && isRising(snapshot)))
        .isEqualTo(breadth.riseCount());
    assertThat(count(batch, snapshot -> !snapshot.security().isSuspended() && isFalling(snapshot)))
        .isEqualTo(breadth.fallCount());
    assertThat(count(batch, snapshot ->
        !snapshot.security().isSuspended() && !isRising(snapshot) && !isFalling(snapshot)))
        .isEqualTo(breadth.flatCount());
  }

  /** 同一批次共用一个版本号与一个行情时间，这是"整个榜单使用同一快照版本"的落点。 */
  @Test
  void sharesOneSequenceAndDataTimeAcrossTheBatch() {
    List<QuoteSnapshot> batch = provider.fetchBatch(MARKET);

    assertThat(batch.stream().map(QuoteSnapshot::sequence).collect(Collectors.toSet()))
        .containsExactly("sim-" + TRADE_DATE);
    assertThat(batch.stream().map(QuoteSnapshot::dataTime).collect(Collectors.toSet()))
        .containsExactly(batch.get(0).dataTime());
    assertThat(batch).allSatisfy(snapshot ->
        assertThat(snapshot.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME));
  }

  // ---------- 榜单口径 ----------

  /**
   * 涨幅榜榜首必须是一只**涨停股**，且其最新价恰好等于涨停价。
   *
   * <p>三所限幅不同（主板 10%、创业板/科创板 20%、北交所 30%），因此榜首必然落在限幅最大的
   * 北交所——这不是巧合，而是"涨停股最新价恰好等于涨停价"的直接后果。
   */
  @Test
  void putsLimitUpSecurityOfTheWidestBandAtTheTopOfGainers() {
    QuoteSnapshot top = ranking("GAINERS", 1, 1).items().get(0);
    SecurityQuote quote = quotesById(quotes.fetchUniverse(MARKET, TRADE_DATE))
        .get(top.security().securityId());
    LimitRule rule = LimitRuleMatcher
        .match(limitRules.rules(MARKET, TRADE_DATE), quote, TRADE_DATE)
        .orElseThrow();

    assertThat(top.security().boardCode()).isEqualTo("BSE");
    assertThat(new BigDecimal(top.latestPrice()))
        .isEqualByComparingTo(rule.limitUpPrice(quote.previousClosePrice()));
    assertThat(new BigDecimal(top.changeRate())).isGreaterThan(new BigDecimal("0.25"));
  }

  /** 跌幅榜是涨幅榜的镜像，榜首必然是限幅最大的北交所跌停股。 */
  @Test
  void putsLimitDownSecurityOfTheWidestBandAtTheTopOfLosers() {
    QuoteSnapshot top = ranking("LOSERS", 1, 1).items().get(0);

    assertThat(top.security().boardCode()).isEqualTo("BSE");
    assertThat(new BigDecimal(top.changeRate())).isLessThan(new BigDecimal("-0.25"));
  }

  // ---------- 小工具 ----------

  private StockRanking ranking(String type, int page, int size) {
    return new StockRankingQueryService(provider)
        .rank(new RankingCriteria(type, null, null, null, null, null, page, size));
  }

  private static long count(List<QuoteSnapshot> batch, Predicate<QuoteSnapshot> predicate) {
    return batch.stream().filter(predicate).count();
  }

  private static boolean isRising(QuoteSnapshot snapshot) {
    return new BigDecimal(snapshot.changeAmount()).signum() > 0;
  }

  private static boolean isFalling(QuoteSnapshot snapshot) {
    return new BigDecimal(snapshot.changeAmount()).signum() < 0;
  }

  private static Map<String, QuoteSnapshot> snapshotsById(List<QuoteSnapshot> snapshots) {
    return snapshots.stream().collect(Collectors.toMap(
        snapshot -> snapshot.security().securityId(), snapshot -> snapshot));
  }

  private static Map<String, SecurityQuote> quotesById(List<SecurityQuote> universe) {
    return universe.stream().collect(Collectors.toMap(SecurityQuote::securityId, quote -> quote));
  }
}
