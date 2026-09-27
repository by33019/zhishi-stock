package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.IntradayPoint;
import cn.zhishi.stock.market.domain.IntradayRequest;
import cn.zhishi.stock.market.domain.IntradaySeries;
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
 * 分时序列的确定性模拟（STK-06）。
 *
 * <h2>本类钉的是"形状合法性"</h2>
 * 240 个一分钟槽位、跨午休不混桶、区间聚合的 OHLC 边界、
 * 确定性（同请求同结果）、价格夹在昨收 ±10% 内。
 * 同一批夹具也服务于 K 线模拟（同一套 SimulatedMarketAccess）。
 */
class SimulatedIntradayProviderTest {

  /** 2026-09-27 是周六，最近交易日为 2026-09-25（周五）。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-26T02:00:00Z"), ZoneOffset.ofHours(8));

  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 25);
  private static final String SECURITY_ID = "sim-600000";

  private final SimulatedSecurityQuoteProvider quotes =
      new SimulatedSecurityQuoteProvider(new SimulatedLimitRuleProvider());
  private final SimulatedTradingCalendarProvider calendar =
      new SimulatedTradingCalendarProvider(CLOCK, Set.of());
  private final SimulatedSecurityMasterProvider masters =
      new SimulatedSecurityMasterProvider(quotes, calendar, CLOCK);

  private final SimulatedIntradayProvider provider =
      new SimulatedIntradayProvider(quotes, masters, calendar, CLOCK);

  @Test
  void oneMinuteIntervalCoversTwoSessionsWith240ClosedMinutes() {
    IntradaySeries series = fetch(1);

    assertThat(series.points()).hasSize(240);
    // 上午 09:30 开盘、11:29 最后一点（11:30 已收盘闭合到 11:29 结束）
    assertThat(series.points().get(0).time())
        .isEqualTo(java.time.LocalDateTime.of(TRADE_DATE, java.time.LocalTime.of(9, 30)));
    // 午休分界：上午最后一点 11:29，下午第一点 13:00——中间不出现 11:30~12:59
    List<IntradayPoint> afternoon = series.points().stream()
        .filter(point -> point.time().getHour() >= 13)
        .toList();
    assertThat(afternoon).hasSize(120);
    assertThat(afternoon.get(0).time())
        .isEqualTo(java.time.LocalDateTime.of(TRADE_DATE, java.time.LocalTime.of(13, 0)));
  }

  @Test
  void fiveMinuteIntervalAggregatesTo48Buckets() {
    IntradaySeries series = fetch(5);

    assertThat(series.points()).hasSize(48);
    IntradayPoint first = series.points().get(0);
    // 5 分钟桶：高低点覆盖桶内所有分钟，收=桶内最后一分钟的收
    assertThat(decimal(first.highPrice()))
        .isGreaterThanOrEqualTo(decimal(first.closePrice()));
    assertThat(decimal(first.lowPrice()))
        .isLessThanOrEqualTo(decimal(first.openPrice()));
  }

  @Test
  void sixtyMinuteIntervalYieldsTwoBucketsPerSession() {
    IntradaySeries series = fetch(60);
    assertThat(series.points()).hasSize(4);
  }

  @Test
  void pricesStayWithinTenPercentOfPreviousClose() {
    IntradaySeries series = fetch(1);
    BigDecimal floor = decimal(series.previousClosePrice())
        .multiply(new BigDecimal("0.90"));
    BigDecimal ceiling = decimal(series.previousClosePrice())
        .multiply(new BigDecimal("1.10"));

    for (IntradayPoint point : series.points()) {
      assertThat(decimal(point.closePrice())).isBetween(floor, ceiling);
    }
  }

  /** 确定性：同请求同结果——这是模拟数据"可测试"的根基。 */
  @Test
  void theSameRequestProducesTheSamePoints() {
    List<IntradayPoint> first = fetch(1).points();
    List<IntradayPoint> second = fetch(1).points();
    assertThat(first).isEqualTo(second);
  }

  @Test
  void previousCloseMatchesTheDayQuote() {
    IntradaySeries series = fetch(1);

    SecurityQuote quote = marketQuote(SECURITY_ID, TRADE_DATE);
    assertThat(decimal(series.previousClosePrice()))
        .isEqualByComparingTo(quote.previousClosePrice());
  }

  // ---------- 夹具 ----------

  private IntradaySeries fetch(int interval) {
    return provider.fetch(new IntradayRequest(SECURITY_ID, "CN", TRADE_DATE, interval))
        .orElseThrow();
  }

  private SecurityQuote marketQuote(String securityId, LocalDate tradeDate) {
    return new SimulatedMarketAccess(quotes, masters, calendar, CLOCK)
        .quote(securityId, tradeDate)
        .orElseThrow();
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
