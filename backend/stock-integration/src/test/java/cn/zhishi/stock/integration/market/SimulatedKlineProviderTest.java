package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.KlineAdjustment;
import cn.zhishi.stock.market.domain.KlinePeriod;
import cn.zhishi.stock.market.domain.KlinePoint;
import cn.zhishi.stock.market.domain.KlineRequest;
import cn.zhishi.stock.market.domain.KlineSeries;
import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.SecurityQuote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 模拟 K 线源测试。
 *
 * <p>重点不在"数字长什么样"，而在四条**结构约束**（对应 spec §8）：
 * <ol>
 *   <li>日 K 末端收盘价 == 该证券快照的最新价（否则头部与 K 线自相矛盾）；</li>
 *   <li>日 K 倒数第二根收盘价 == 该证券的前收价（否则与广度口径分叉）；</li>
 *   <li>周/月 K 由日 K 聚合而来，不是独立生成；</li>
 *   <li>开高低始终落在涨跌停价之内。</li>
 * </ol>
 */
class SimulatedKlineProviderTest {

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

  private final SimulatedKlineProvider provider =
      new SimulatedKlineProvider(quotes, masters, rules, calendar, CLOCK);

  // ---------- 末端锚定 ----------

  @Test
  void anchorsLastDailyCloseToLatestPrice() {
    SecurityQuote quote = quoteOf(SECURITY_ID, ANCHOR);

    List<KlinePoint> points = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(20), ANCHOR);

    KlinePoint last = points.get(points.size() - 1);
    assertThat(last.time()).isEqualTo(ANCHOR);
    assertThat(decimal(last.closePrice())).isEqualByComparingTo(quote.latestPrice());
  }

  @Test
  void anchorsSecondToLastCloseToPreviousClose() {
    SecurityQuote quote = quoteOf(SECURITY_ID, ANCHOR);

    List<KlinePoint> points = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(20), ANCHOR);

    KlinePoint secondToLast = points.get(points.size() - 2);
    assertThat(decimal(secondToLast.closePrice()))
        .isEqualByComparingTo(quote.previousClosePrice());
  }

  @Test
  void isDeterministicAcrossCalls() {
    List<KlinePoint> first = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(30), ANCHOR);
    List<KlinePoint> second = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(30), ANCHOR);

    assertThat(first).isEqualTo(second);
  }

  /** 同一时刻查询不同区间，重叠部分必须逐点相同（倒推起点一致）。 */
  @Test
  void keepsOverlappingRangesConsistent() {
    LocalDate start = ANCHOR.minusDays(30);
    List<KlinePoint> wide = fetch(SECURITY_ID, KlinePeriod.DAY, start, ANCHOR);
    List<KlinePoint> narrow = fetch(SECURITY_ID, KlinePeriod.DAY, start.plusDays(10), ANCHOR);

    assertThat(wide).endsWith(narrow.toArray(new KlinePoint[0]));
  }

  // ---------- 价格结构 ----------

  @Test
  void keepsOpenHighLowConsistentWithinEachPoint() {
    List<KlinePoint> points = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(40), ANCHOR);

    assertThat(points).isNotEmpty();
    for (KlinePoint point : points) {
      BigDecimal open = decimal(point.openPrice());
      BigDecimal close = decimal(point.closePrice());
      BigDecimal high = decimal(point.highPrice());
      BigDecimal low = decimal(point.lowPrice());

      assertThat(high).isGreaterThanOrEqualTo(open.max(close));
      assertThat(low).isLessThanOrEqualTo(open.min(close));
    }
  }

  @Test
  void keepsHighAndLowWithinLimitPrices() {
    List<KlinePoint> points = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(40), ANCHOR);

    assertThat(points).isNotEmpty();
    for (KlinePoint point : points) {
      BigDecimal previousClose = decimal(point.previousClosePrice());
      SecurityQuote probe = quoteOf(SECURITY_ID, point.time());
      LimitRule rule = LimitRuleMatcher
          .match(rules.rules("CN", point.time()), probe, point.time())
          .orElseThrow();

      assertThat(decimal(point.highPrice()))
          .isLessThanOrEqualTo(rule.limitUpPrice(previousClose));
      assertThat(decimal(point.lowPrice()))
          .isGreaterThanOrEqualTo(rule.limitDownPrice(previousClose));
    }
  }

  @Test
  void keepsSuspendedSecurityPriceNonZero() {
    SecurityQuote suspended = quotes.fetchUniverse("CN", ANCHOR).stream()
        .filter(SecurityQuote::suspended)
        .findFirst()
        .orElseThrow();

    List<KlinePoint> points = fetch(
        suspended.securityId(), KlinePeriod.DAY, ANCHOR.minusDays(5), ANCHOR);

    assertThat(points).isNotEmpty();
    for (KlinePoint point : points) {
      assertThat(decimal(point.closePrice())).isGreaterThan(BigDecimal.ZERO);
    }
  }

  @Test
  void reportsChangeAgainstPreviousClose() {
    List<KlinePoint> points = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(20), ANCHOR);

    for (KlinePoint point : points) {
      BigDecimal previousClose = decimal(point.previousClosePrice());
      BigDecimal close = decimal(point.closePrice());
      assertThat(decimal(point.changeAmount())).isEqualByComparingTo(close.subtract(previousClose));
    }
  }

  // ---------- 周 / 月聚合 ----------

  @Test
  void aggregatesWeekCloseFromLastDailyCloseOfThatWeek() {
    List<KlinePoint> daily = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(90), ANCHOR);
    List<KlinePoint> weekly = fetch(SECURITY_ID, KlinePeriod.WEEK, ANCHOR.minusDays(90), ANCHOR);

    assertThat(weekly).isNotEmpty();
    for (KlinePoint week : weekly) {
      // 周期点的 time 就是该周期最后一个交易日，因此它必然也出现在日 K 序列里
      KlinePoint lastOfWeek = daily.stream()
          .filter(point -> point.time().equals(week.time()))
          .findFirst()
          .orElseThrow();
      assertThat(decimal(week.closePrice()))
          .isEqualByComparingTo(decimal(lastOfWeek.closePrice()));
    }
  }

  @Test
  void aggregatesWeekHighAsMaxOfDailyHighs() {
    LocalDate start = ANCHOR.minusDays(40);
    List<KlinePoint> daily = fetch(SECURITY_ID, KlinePeriod.DAY, start, ANCHOR);
    List<KlinePoint> weekly = fetch(SECURITY_ID, KlinePeriod.WEEK, start, ANCHOR);

    BigDecimal maxDailyHigh = daily.stream()
        .map(point -> decimal(point.highPrice()))
        .max(BigDecimal::compareTo)
        .orElseThrow();
    BigDecimal maxWeeklyHigh = weekly.stream()
        .map(point -> decimal(point.highPrice()))
        .max(BigDecimal::compareTo)
        .orElseThrow();

    assertThat(maxWeeklyHigh).isEqualByComparingTo(maxDailyHigh);
  }

  @Test
  void aggregatesMonthFromDaily() {
    LocalDate start = ANCHOR.minusDays(120);
    List<KlinePoint> daily = fetch(SECURITY_ID, KlinePeriod.DAY, start, ANCHOR);
    List<KlinePoint> monthly = fetch(SECURITY_ID, KlinePeriod.MONTH, start, ANCHOR);

    assertThat(monthly).isNotEmpty();
    KlinePoint lastMonth = monthly.get(monthly.size() - 1);
    KlinePoint lastDay = daily.get(daily.size() - 1);

    assertThat(lastMonth.closePrice()).isEqualTo(lastDay.closePrice());
    assertThat(lastMonth.time().getMonth()).isEqualTo(lastDay.time().getMonth());
  }

  /** 周期点的 time 必须是该周期内**最后一个交易日**，而不是首日或自然月末。 */
  @Test
  void usesLastTradingDayOfPeriodAsTime() {
    LocalDate start = ANCHOR.minusDays(90);
    List<KlinePoint> weekly = fetch(SECURITY_ID, KlinePeriod.WEEK, start, ANCHOR);
    List<KlinePoint> daily = fetch(SECURITY_ID, KlinePeriod.DAY, start, ANCHOR);

    assertThat(weekly).isNotEmpty();
    for (KlinePoint week : weekly) {
      assertThat(daily).anySatisfy(day -> assertThat(day.time()).isEqualTo(week.time()));
    }
  }

  @Test
  void returnsPointsInAscendingTimeOrder() {
    List<KlinePoint> daily = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(60), ANCHOR);

    assertThat(daily).isSortedAccordingTo(
        (left, right) -> left.time().compareTo(right.time()));
  }

  @Test
  void doesNotIncludePointsAfterRequestedEndDate() {
    LocalDate end = ANCHOR.minusDays(10);

    List<KlinePoint> points = fetch(SECURITY_ID, KlinePeriod.DAY, ANCHOR.minusDays(40), end);

    assertThat(points).isNotEmpty();
    assertThat(points).allSatisfy(
        point -> assertThat(point.time()).isBeforeOrEqualTo(end));
  }

  // ---------- 空结果 ----------

  @Test
  void returnsEmptyForUnknownSecurity() {
    assertThat(provider.fetch(new KlineRequest(
        "sim-999999", "CN", KlinePeriod.DAY, ANCHOR.minusDays(5), ANCHOR,
        KlineAdjustment.NONE))).isEmpty();
  }

  @Test
  void returnsEmptyForUnsupportedMarket() {
    assertThat(provider.fetch(new KlineRequest(
        SECURITY_ID, "US", KlinePeriod.DAY, ANCHOR.minusDays(5), ANCHOR,
        KlineAdjustment.NONE))).isEmpty();
  }

  // ---------- 辅助 ----------

  private List<KlinePoint> fetch(
      String securityId, KlinePeriod period, LocalDate start, LocalDate end) {
    KlineSeries series = provider.fetch(new KlineRequest(
        securityId, "CN", period, start, end, KlineAdjustment.NONE)).orElseThrow();
    return series.points();
  }

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
