package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.TurnoverRange;
import cn.zhishi.stock.market.domain.TurnoverTrend;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulatedTurnoverTrendProviderTest {

  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

  /** 2026-09-11 是周五，2026-09-10 周四，2026-09-12 周六。 */
  private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);

  private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);

  @Test
  void producesOnePointPerElapsedMinuteDuringTheMorningSession() {
    var trend = fetch(providerAt("2026-09-11T02:00:00Z"), TurnoverRange.TODAY, "1m");

    assertThat(trend.points()).hasSize(30);
    assertThat(trend.points().get(0).time()).isEqualTo("2026-09-11T09:31:00+08:00");
    assertThat(trend.points().get(29).time()).isEqualTo("2026-09-11T10:00:00+08:00");
    assertThat(trend.dataCutoffAt()).isEqualTo(time("2026-09-11T10:00:00+08:00"));
  }

  @Test
  void stopsAtTheMorningCloseAndSkipsTheLunchBreak() {
    var trend = fetch(providerAt("2026-09-11T04:00:00Z"), TurnoverRange.TODAY, "1m");

    assertThat(trend.points()).hasSize(120);
    assertThat(trend.points().get(119).time()).isEqualTo("2026-09-11T11:30:00+08:00");
    assertThat(trend.dataCutoffAt()).isEqualTo(time("2026-09-11T11:30:00+08:00"));
  }

  @Test
  void coversBothSessionsAfterTheClose() {
    var trend = fetch(providerAt("2026-09-11T08:00:00Z"), TurnoverRange.TODAY, "1m");

    assertThat(trend.points()).hasSize(240);
    assertThat(trend.points().get(119).time()).isEqualTo("2026-09-11T11:30:00+08:00");
    assertThat(trend.points().get(120).time()).isEqualTo("2026-09-11T13:01:00+08:00");
    assertThat(trend.points().get(239).time()).isEqualTo("2026-09-11T15:00:00+08:00");
  }

  @Test
  void aggregatesIntoWholeBucketsOnly() {
    var fiveMinute = fetch(providerAt("2026-09-11T02:00:00Z"), TurnoverRange.TODAY, "5m");

    assertThat(fiveMinute.points()).hasSize(6);
    assertThat(fiveMinute.points().get(0).time()).isEqualTo("2026-09-11T09:35:00+08:00");
    assertThat(fiveMinute.points().get(5).time()).isEqualTo("2026-09-11T10:00:00+08:00");

    var halfHour = fetch(providerAt("2026-09-11T04:00:00Z"), TurnoverRange.TODAY, "30m");

    assertThat(halfHour.points()).hasSize(4);
    assertThat(halfHour.points().get(0).time()).isEqualTo("2026-09-11T10:00:00+08:00");
    assertThat(halfHour.points().get(3).time()).isEqualTo("2026-09-11T11:30:00+08:00");
  }

  @Test
  void everyIntradayPointSitsInsideAContinuousTradingSession() {
    var trend = fetch(providerAt("2026-09-11T08:00:00Z"), TurnoverRange.TODAY, "1m");

    assertThat(trend.points()).allSatisfy(point -> {
      LocalTime at = OffsetDateTime.parse(point.time()).toLocalTime();
      boolean morning = !at.isBefore(LocalTime.of(9, 31)) && !at.isAfter(LocalTime.of(11, 30));
      boolean afternoon = !at.isBefore(LocalTime.of(13, 1)) && !at.isAfter(LocalTime.of(15, 0));
      assertThat(morning || afternoon).as("分钟点 %s 落在交易时段之外", point.time()).isTrue();
    });
  }

  @Test
  void cumulativeValuesNeverDecreaseAndReachTheDayTotalAtTheClose() {
    var provider = providerAt("2026-09-11T08:00:00Z");
    var today = fetch(provider, TurnoverRange.TODAY, "1m");
    var fiveDays = fetch(provider, TurnoverRange.FIVE_DAYS, null);

    BigInteger previousAmount = BigInteger.ZERO;
    BigInteger previousVolume = BigInteger.ZERO;
    for (TurnoverTrend.Point point : today.points()) {
      BigInteger amount = new BigInteger(point.tradeAmount());
      BigInteger volume = new BigInteger(point.tradeVolume());
      assertThat(amount).isGreaterThanOrEqualTo(previousAmount);
      assertThat(volume).isGreaterThanOrEqualTo(previousVolume);
      assertThat(amount).isPositive();
      assertThat(volume).isPositive();
      previousAmount = amount;
      previousVolume = volume;
    }

    // 生成与聚合互为逆运算：今日曲线的终点必须等于同一天在日维度上的那个点
    var lastIntraday = today.points().get(today.points().size() - 1);
    var lastDaily = fiveDays.points().get(fiveDays.points().size() - 1);
    assertThat(lastDaily.time()).isEqualTo(FRIDAY.toString());
    assertThat(lastIntraday.tradeAmount()).isEqualTo(lastDaily.tradeAmount());
    assertThat(lastIntraday.tradeVolume()).isEqualTo(lastDaily.tradeVolume());
  }

  @Test
  void fallsBackToThePreviousTradingDayBeforeTheOpen() {
    var trend = fetch(providerAt("2026-09-11T01:00:00Z"), TurnoverRange.TODAY, "1m");

    assertThat(trend.points()).hasSize(240);
    assertThat(trend.points()).allSatisfy(point ->
        assertThat(OffsetDateTime.parse(point.time()).toLocalDate()).isEqualTo(THURSDAY));
    assertThat(trend.dataCutoffAt()).isEqualTo(time("2026-09-10T15:00:00+08:00"));
  }

  @Test
  void fallsBackToThePreviousTradingDayOnWeekends() {
    var trend = fetch(providerAt("2026-09-12T03:00:00Z"), TurnoverRange.TODAY, "1m");

    assertThat(trend.points()).hasSize(240);
    assertThat(trend.points()).allSatisfy(point ->
        assertThat(OffsetDateTime.parse(point.time()).toLocalDate()).isEqualTo(FRIDAY));
    assertThat(trend.dataCutoffAt()).isEqualTo(time("2026-09-11T15:00:00+08:00"));
  }

  @Test
  void returnsOneDailyPointPerTradingDay() {
    var provider = providerAt("2026-09-11T08:00:00Z");

    var fiveDays = fetch(provider, TurnoverRange.FIVE_DAYS, null);
    assertThat(fiveDays.points()).hasSize(5);
    assertThat(fiveDays.interval()).isNull();
    assertThat(fiveDays.dataCutoffAt()).isEqualTo(time("2026-09-11T15:00:00+08:00"));

    var twentyDays = fetch(provider, TurnoverRange.TWENTY_DAYS, null);
    assertThat(twentyDays.points()).hasSize(20);

    List<LocalDate> dates = twentyDays.points().stream()
        .map(point -> LocalDate.parse(point.time()))
        .toList();
    assertThat(dates).isSorted();
    assertThat(dates).allSatisfy(date ->
        assertThat(date.getDayOfWeek().getValue()).isLessThanOrEqualTo(5));
    assertThat(dates.get(dates.size() - 1)).isEqualTo(FRIDAY);
    assertThat(twentyDays.points()).allSatisfy(point -> {
      assertThat(new BigInteger(point.tradeAmount())).isPositive();
      assertThat(new BigInteger(point.tradeVolume())).isPositive();
    });
  }

  @Test
  void excludesTheStillRunningTradingDayFromDailyRanges() {
    var midSession = fetch(providerAt("2026-09-11T02:00:00Z"), TurnoverRange.FIVE_DAYS, null);
    var afterClose = fetch(providerAt("2026-09-11T08:00:00Z"), TurnoverRange.FIVE_DAYS, null);

    assertThat(midSession.points().get(midSession.points().size() - 1).time())
        .isEqualTo(THURSDAY.toString());
    assertThat(midSession.dataCutoffAt()).isEqualTo(time("2026-09-10T15:00:00+08:00"));
    assertThat(afterClose.points().get(afterClose.points().size() - 1).time())
        .isEqualTo(FRIDAY.toString());
  }

  @Test
  void declaresUnitsExplicitly() {
    var trend = fetch(providerAt("2026-09-11T08:00:00Z"), TurnoverRange.TODAY, "1m");

    assertThat(trend.unit().tradeAmount()).isEqualTo("CNY");
    assertThat(trend.unit().tradeVolume()).isEqualTo("SHARE");
  }

  @Test
  void isDeterministicAcrossCalls() {
    var provider = providerAt("2026-09-11T08:00:00Z");

    assertThat(provider.fetch("CN", TurnoverRange.TODAY, "1m"))
        .isEqualTo(provider.fetch("CN", TurnoverRange.TODAY, "1m"));
    assertThat(provider.fetch("CN", TurnoverRange.TWENTY_DAYS, null))
        .isEqualTo(provider.fetch("CN", TurnoverRange.TWENTY_DAYS, null));
  }

  @Test
  void rejectsUnsupportedMarket() {
    assertThat(providerAt("2026-09-11T08:00:00Z").fetch("US", TurnoverRange.TODAY, "1m"))
        .isEmpty();
  }

  private static SimulatedTurnoverTrendProvider providerAt(String instant) {
    Clock clock = Clock.fixed(Instant.parse(instant), SHANGHAI);
    return new SimulatedTurnoverTrendProvider(
        clock, new SimulatedTradingCalendarProvider(clock, Set.of()));
  }

  private static TurnoverTrend fetch(
      SimulatedTurnoverTrendProvider provider, TurnoverRange range, String interval) {
    return provider.fetch("CN", range, interval)
        .orElseThrow(() -> new AssertionError("未取到趋势数据：" + range));
  }

  private static OffsetDateTime time(String isoOffsetDateTime) {
    return OffsetDateTime.parse(isoOffsetDateTime);
  }
}
