package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.KlineAdjustment;
import cn.zhishi.stock.market.domain.KlinePeriod;
import cn.zhishi.stock.market.domain.KlineProvider;
import cn.zhishi.stock.market.domain.KlineRequest;
import cn.zhishi.stock.market.domain.KlineSeries;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * M2-05 用例层测试：参数校验与错误语义。
 *
 * <p>这里刻意**不测**价格生成（那是 Provider 的职责，见
 * {@code SimulatedQuoteSnapshotProviderTest} / {@code SimulatedKlineProviderTest}）。
 * 本类只回答一个问题：用例层把什么样的输入转成了什么样的错误。
 *
 * <p>桩日历把"周一至周五"当作交易日，与真实节假日安排无关——
 * 用例层不关心具体哪天开市，只关心"往前数 N 个交易日"这件事算得对不对。
 */
class SecurityDetailQueryServiceTest {

  /** 2026-09-19 是周六，因此最近交易日应为 2026-09-18（周五）。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-19T02:00:00Z"), ZoneOffset.ofHours(8));

  private static final LocalDate LATEST_TRADE_DATE = LocalDate.of(2026, 9, 18);

  private static final TradingCalendarProvider CALENDAR = (marketCode, date) -> {
    if (!"CN".equals(marketCode)) {
      return Optional.empty();
    }
    return Optional.of(new TradingCalendarDay(
        date,
        isWeekday(date),
        previousWeekday(date),
        nextWeekday(date),
        List.of(),
        OffsetDateTime.now(CLOCK)));
  };

  // ---------- STK-04 快照 ----------

  @Test
  void returnsSnapshotForKnownSecurity() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), true);

    QuoteSnapshot snapshot = service.getQuote("sim-600000");

    assertThat(snapshot.security().securityId()).isEqualTo("sim-600000");
    assertThat(snapshot.latestPrice()).isEqualTo("12.34");
  }

  @Test
  void trimsSecurityIdBeforeQuerying() {
    RecordingSnapshotProvider provider = new RecordingSnapshotProvider(snapshot("sim-600000"));
    SecurityDetailQueryService service =
        new SecurityDetailQueryService(provider, new RecordingKlineProvider(true), CALENDAR, CLOCK);

    service.getQuote("  sim-600000  ");

    assertThat(provider.receivedSecurityId).isEqualTo("sim-600000");
  }

  @Test
  void reportsUnknownSecurityAsNotFound() {
    SecurityDetailQueryService service = serviceWith(null, true);

    assertThatThrownBy(() -> service.getQuote("sim-999999"))
        .isInstanceOf(SecurityNotFoundException.class)
        .hasMessageContaining("sim-999999");
  }

  @Test
  void reportsBlankSecurityIdAsNotFound() {
    SecurityDetailQueryService service = serviceWith(null, true);

    assertThatThrownBy(() -> service.getQuote("   "))
        .isInstanceOf(SecurityNotFoundException.class);
  }

  // ---------- STK-07 参数校验 ----------

  @Test
  void requiresSupportedPeriod() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), true);

    assertThatThrownBy(() -> service.getKlines("sim-600000", null, null, null, null))
        .isInstanceOf(InvalidKlineParameterException.class)
        .hasMessageContaining("period");
    assertThatThrownBy(() -> service.getKlines("sim-600000", "HOUR", null, null, null))
        .isInstanceOf(InvalidKlineParameterException.class)
        .hasMessageContaining("period");
  }

  @Test
  void defaultsAdjustmentToNone() {
    RecordingKlineProvider provider = new RecordingKlineProvider(true);
    SecurityDetailQueryService service =
        new SecurityDetailQueryService(
            new RecordingSnapshotProvider(snapshot("sim-600000")), provider, CALENDAR, CLOCK);

    service.getKlines("sim-600000", "DAY", "2026-01-05", "2026-06-30", null);

    assertThat(provider.received.adjustment()).isEqualTo(KlineAdjustment.NONE);
  }

  @Test
  void acceptsExplicitNoneAdjustmentCaseInsensitively() {
    RecordingKlineProvider provider = new RecordingKlineProvider(true);
    SecurityDetailQueryService service =
        new SecurityDetailQueryService(
            new RecordingSnapshotProvider(snapshot("sim-600000")), provider, CALENDAR, CLOCK);

    service.getKlines("sim-600000", "day", "2026-01-05", "2026-06-30", "none");

    assertThat(provider.received.period()).isEqualTo(KlinePeriod.DAY);
    assertThat(provider.received.adjustment()).isEqualTo(KlineAdjustment.NONE);
  }

  /**
   * 不支持的复权方式必须明确报错。
   *
   * <p>若静默降级为 {@code NONE}，调用方会以为自己拿到的是前复权数据——
   * 这是最难排查的一类问题（§8.3 明确禁止静默替换）。
   */
  @Test
  void rejectsUnsupportedAdjustmentInsteadOfSilentlyFallingBack() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), true);

    assertThatThrownBy(() -> service.getKlines("sim-600000", "DAY", null, null, "FORWARD"))
        .isInstanceOf(InvalidKlineParameterException.class)
        .satisfies(exception -> assertThat(((InvalidKlineParameterException) exception).code())
            .isEqualTo("ADJUSTMENT_NOT_SUPPORTED"))
        .hasMessageContaining("NONE");
  }

  @Test
  void rejectsStartDateAfterEndDate() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), true);

    assertThatThrownBy(() -> service.getKlines(
        "sim-600000", "DAY", "2026-06-30", "2026-01-05", null))
        .isInstanceOf(InvalidKlineParameterException.class)
        .hasMessageContaining("startDate");
  }

  @Test
  void rejectsMalformedDate() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), true);

    assertThatThrownBy(() -> service.getKlines(
        "sim-600000", "DAY", "2026/01/05", "2026-06-30", null))
        .isInstanceOf(InvalidKlineParameterException.class)
        .hasMessageContaining("yyyy-MM-dd");
  }

  @Test
  void rejectsDayRangeBeyondTenYears() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), true);

    assertThatThrownBy(() -> service.getKlines(
        "sim-600000", "DAY", "2015-09-18", "2026-09-18", null))
        .isInstanceOf(InvalidKlineParameterException.class)
        .satisfies(exception -> assertThat(((InvalidKlineParameterException) exception).code())
            .isEqualTo("KLINE_RANGE_TOO_LARGE"))
        .hasMessageContaining("10 年");
  }

  /** 周 K 的上限是 20 年，同样 11 年的区间对周 K 必须放行。 */
  @Test
  void acceptsWeekRangeThatDayPeriodWouldReject() {
    RecordingKlineProvider provider = new RecordingKlineProvider(true);
    SecurityDetailQueryService service =
        new SecurityDetailQueryService(
            new RecordingSnapshotProvider(snapshot("sim-600000")), provider, CALENDAR, CLOCK);

    service.getKlines("sim-600000", "WEEK", "2015-09-18", "2026-09-18", null);

    assertThat(provider.received.startDate()).isEqualTo(LocalDate.of(2015, 9, 18));
  }

  @Test
  void rejectsMonthRangeBeyondTwentyYears() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), true);

    assertThatThrownBy(() -> service.getKlines(
        "sim-600000", "MONTH", "2000-01-01", "2026-09-18", null))
        .isInstanceOf(InvalidKlineParameterException.class)
        .hasMessageContaining("20 年");
  }

  @Test
  void reportsUnknownSecurityAsNotFoundForKlines() {
    SecurityDetailQueryService service = serviceWith(snapshot("sim-600000"), false);

    assertThatThrownBy(() -> service.getKlines("sim-999999", "DAY", null, null, null))
        .isInstanceOf(SecurityNotFoundException.class);
  }

  // ---------- 默认区间 ----------

  @Test
  void defaultsToRecent120TradingDaysEndingAtLatestTradeDate() {
    RecordingKlineProvider provider = new RecordingKlineProvider(true);
    SecurityDetailQueryService service =
        new SecurityDetailQueryService(
            new RecordingSnapshotProvider(snapshot("sim-600000")), provider, CALENDAR, CLOCK);

    service.getKlines("sim-600000", "DAY", null, null, null);

    assertThat(provider.received.endDate()).isEqualTo(LATEST_TRADE_DATE);
    assertThat(tradingDaysBetween(provider.received.startDate(), provider.received.endDate()))
        .isEqualTo(120);
  }

  @Test
  void defaultsEndDateToLatestTradeDateWhenOnlyStartGiven() {
    RecordingKlineProvider provider = new RecordingKlineProvider(true);
    SecurityDetailQueryService service =
        new SecurityDetailQueryService(
            new RecordingSnapshotProvider(snapshot("sim-600000")), provider, CALENDAR, CLOCK);

    service.getKlines("sim-600000", "DAY", "2026-01-05", null, null);

    assertThat(provider.received.startDate()).isEqualTo(LocalDate.of(2026, 1, 5));
    assertThat(provider.received.endDate()).isEqualTo(LATEST_TRADE_DATE);
  }

  @Test
  void passesNormalizedRequestToProvider() {
    RecordingKlineProvider provider = new RecordingKlineProvider(true);
    SecurityDetailQueryService service =
        new SecurityDetailQueryService(
            new RecordingSnapshotProvider(snapshot("sim-600000")), provider, CALENDAR, CLOCK);

    service.getKlines("sim-600000", "week", "2026-01-05", "2026-06-30", "NONE");

    assertThat(provider.received).isEqualTo(new KlineRequest(
        "sim-600000",
        "CN",
        KlinePeriod.WEEK,
        LocalDate.of(2026, 1, 5),
        LocalDate.of(2026, 6, 30),
        KlineAdjustment.NONE));
  }

  // ---------- 辅助 ----------

  private static SecurityDetailQueryService serviceWith(
      QuoteSnapshot snapshot, boolean klinePresent) {
    return new SecurityDetailQueryService(
        new RecordingSnapshotProvider(snapshot),
        new RecordingKlineProvider(klinePresent),
        CALENDAR,
        CLOCK);
  }

  private static SecuritySummary summary(String securityId) {
    return new SecuritySummary(
        securityId, "SH.600000", "600000", "模拟证券600000", "SH", "STOCK", "MAIN",
        "LISTED", false, false, 2, null, null);
  }

  private static QuoteSnapshot snapshot(String securityId) {
    return new QuoteSnapshot(
        summary(securityId), "12.00", "12.10", "12.34", "12.50", "11.90", "0.34", "0.0283",
        "1200000", "14700000", "0.0125",
        OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK), "sim-2026-09-18",
        MarketOverview.DataStatus.REALTIME, null);
  }

  private static long tradingDaysBetween(LocalDate start, LocalDate end) {
    return start.datesUntil(end.plusDays(1)).filter(SecurityDetailQueryServiceTest::isWeekday).count();
  }

  private static boolean isWeekday(LocalDate date) {
    DayOfWeek day = date.getDayOfWeek();
    return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
  }

  private static LocalDate previousWeekday(LocalDate date) {
    LocalDate cursor = date.minusDays(1);
    while (!isWeekday(cursor)) {
      cursor = cursor.minusDays(1);
    }
    return cursor;
  }

  private static LocalDate nextWeekday(LocalDate date) {
    LocalDate cursor = date.plusDays(1);
    while (!isWeekday(cursor)) {
      cursor = cursor.plusDays(1);
    }
    return cursor;
  }

  private static final class RecordingSnapshotProvider implements QuoteSnapshotProvider {

    private final QuoteSnapshot snapshot;
    private String receivedSecurityId;

    private RecordingSnapshotProvider(QuoteSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public Optional<QuoteSnapshot> fetch(String securityId, String marketCode) {
      this.receivedSecurityId = securityId;
      if (snapshot == null || !snapshot.security().securityId().equals(securityId)) {
        return Optional.empty();
      }
      return Optional.of(snapshot);
    }
  }

  private static final class RecordingKlineProvider implements KlineProvider {

    private final boolean present;
    private KlineRequest received;

    private RecordingKlineProvider(boolean present) {
      this.present = present;
    }

    @Override
    public Optional<KlineSeries> fetch(KlineRequest request) {
      this.received = request;
      if (!present) {
        return Optional.empty();
      }
      return Optional.of(new KlineSeries(
          summary(request.securityId()),
          request.period(),
          request.adjustment(),
          OffsetDateTime.now(CLOCK),
          MarketOverview.DataStatus.REALTIME,
          List.of()));
    }
  }
}
