package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.KlineAdjustment;
import cn.zhishi.stock.market.domain.KlinePeriod;
import cn.zhishi.stock.market.domain.KlinePoint;
import cn.zhishi.stock.market.domain.KlineRequest;
import cn.zhishi.stock.market.domain.KlineSeries;
import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 模拟个股快照源测试。
 *
 * <p>核心是**跨来源一致性**：快照里的每一个数都必须与行情全集（广度口径）、
 * 证券主数据、K 线末端对得上。这些约束一旦失守，用户会在不同页面看到
 * 同一只证券的不同价格——而且不会有任何测试变红。
 */
class SimulatedQuoteSnapshotProviderTest {

  /** 2026-09-19 是周六，最近交易日为 2026-09-18（周五）。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-19T02:00:00Z"), ZoneOffset.ofHours(8));

  private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 18);

  private static final String SECURITY_ID = "sim-600000";

  private final SimulatedLimitRuleProvider rules = new SimulatedLimitRuleProvider();

  private final SimulatedSecurityQuoteProvider quotes =
      new SimulatedSecurityQuoteProvider(rules);

  private final SimulatedTradingCalendarProvider calendar =
      new SimulatedTradingCalendarProvider(CLOCK, Set.of());

  private final SimulatedSecurityMasterProvider masters =
      new SimulatedSecurityMasterProvider(quotes, calendar, CLOCK);

  private final SimulatedQuoteSnapshotProvider provider =
      new SimulatedQuoteSnapshotProvider(quotes, masters, rules, calendar, CLOCK);

  private final SimulatedKlineProvider klines =
      new SimulatedKlineProvider(quotes, masters, rules, calendar, CLOCK);

  // ---------- 与行情全集（广度口径）一致 ----------

  @Test
  void mirrorsLatestPriceFromQuoteUniverse() {
    SecurityQuote quote = quoteOf(SECURITY_ID, ANCHOR);

    QuoteSnapshot snapshot = provider.fetch(SECURITY_ID, "CN").orElseThrow();

    assertThat(decimal(snapshot.latestPrice())).isEqualByComparingTo(quote.latestPrice());
  }

  @Test
  void mirrorsPreviousCloseFromQuoteUniverse() {
    SecurityQuote quote = quoteOf(SECURITY_ID, ANCHOR);

    QuoteSnapshot snapshot = provider.fetch(SECURITY_ID, "CN").orElseThrow();

    assertThat(decimal(snapshot.previousClosePrice()))
        .isEqualByComparingTo(quote.previousClosePrice());
  }

  /** 证券身份必须投影自主数据，而不是自己拼一份——否则两个视图会指向不同事实。 */
  @Test
  void mirrorsSecurityIdentityFromMasterData() {
    SecuritySummary expected = masters.findAll("CN").stream()
        .filter(summary -> summary.securityId().equals(SECURITY_ID))
        .findFirst()
        .orElseThrow();

    QuoteSnapshot snapshot = provider.fetch(SECURITY_ID, "CN").orElseThrow();

    assertThat(snapshot.security()).isEqualTo(expected);
  }

  /** 行情头部显示的价格，必须与 K 线最后一根收盘价完全相同。 */
  @Test
  void agreesWithLastDailyClose() {
    QuoteSnapshot snapshot = provider.fetch(SECURITY_ID, "CN").orElseThrow();

    KlineSeries series = klines.fetch(new KlineRequest(
        SECURITY_ID, "CN", KlinePeriod.DAY, ANCHOR.minusDays(30), ANCHOR,
        KlineAdjustment.NONE)).orElseThrow();
    KlinePoint last = series.points().get(series.points().size() - 1);

    assertThat(decimal(snapshot.latestPrice()))
        .isEqualByComparingTo(decimal(last.closePrice()));
  }

  @Test
  void sharesSequenceAcrossSecuritiesOfSameBatch() {
    QuoteSnapshot first = provider.fetch("sim-600000", "CN").orElseThrow();
    QuoteSnapshot second = provider.fetch("sim-600001", "CN").orElseThrow();

    assertThat(first.sequence()).isEqualTo(second.sequence());
  }

  // ---------- 价格结构 ----------

  @Test
  void keepsHighAndLowAroundLatestPriceWithinLimitPrices() {
    SecurityQuote quote = quoteOf(SECURITY_ID, ANCHOR);
    LimitRule rule = LimitRuleMatcher
        .match(rules.rules("CN", ANCHOR), quote, ANCHOR)
        .orElseThrow();

    QuoteSnapshot snapshot = provider.fetch(SECURITY_ID, "CN").orElseThrow();
    BigDecimal previousClose = decimal(snapshot.previousClosePrice());
    BigDecimal latest = decimal(snapshot.latestPrice());

    assertThat(decimal(snapshot.highPrice())).isGreaterThanOrEqualTo(latest);
    assertThat(decimal(snapshot.lowPrice())).isLessThanOrEqualTo(latest);
    assertThat(decimal(snapshot.highPrice()))
        .isLessThanOrEqualTo(rule.limitUpPrice(previousClose));
    assertThat(decimal(snapshot.lowPrice()))
        .isGreaterThanOrEqualTo(rule.limitDownPrice(previousClose));
  }

  @Test
  void reportsChangeAmountConsistentWithPrices() {
    QuoteSnapshot snapshot = provider.fetch(SECURITY_ID, "CN").orElseThrow();

    BigDecimal expected = decimal(snapshot.latestPrice())
        .subtract(decimal(snapshot.previousClosePrice()));

    assertThat(decimal(snapshot.changeAmount())).isEqualByComparingTo(expected);
  }

  @Test
  void keepsSuspendedSecurityPriceNonZero() {
    SecurityQuote suspended = quotes.fetchUniverse("CN", ANCHOR).stream()
        .filter(SecurityQuote::suspended)
        .findFirst()
        .orElseThrow();

    QuoteSnapshot snapshot = provider.fetch(suspended.securityId(), "CN").orElseThrow();

    assertThat(snapshot.security().isSuspended()).isTrue();
    assertThat(decimal(snapshot.latestPrice())).isGreaterThan(BigDecimal.ZERO);
    assertThat(decimal(snapshot.latestPrice()))
        .isEqualByComparingTo(decimal(snapshot.previousClosePrice()));
  }

  @Test
  void isFullyDeterministic() {
    assertThat(provider.fetch(SECURITY_ID, "CN"))
        .isEqualTo(provider.fetch(SECURITY_ID, "CN"));
  }

  // ---------- 空结果 ----------

  @Test
  void returnsEmptyForUnknownSecurity() {
    assertThat(provider.fetch("sim-999999", "CN")).isEmpty();
  }

  @Test
  void returnsEmptyForUnsupportedMarket() {
    assertThat(provider.fetch(SECURITY_ID, "US")).isEmpty();
  }

  @Test
  void returnsEmptyForNullSecurityId() {
    assertThat(provider.fetch(null, "CN")).isEmpty();
  }

  // ---------- 辅助 ----------

  private SecurityQuote quoteOf(String securityId, LocalDate tradeDate) {
    return quotes.fetchUniverse("CN", tradeDate).stream()
        .filter(quote -> quote.securityId().equals(securityId))
        .findFirst()
        .orElseThrow();
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
