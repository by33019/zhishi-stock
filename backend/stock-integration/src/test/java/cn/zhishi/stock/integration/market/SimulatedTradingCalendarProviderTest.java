package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingSession;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulatedTradingCalendarProviderTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  /** 2026-09-11 是周五。 */
  private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);

  private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);

  private final SimulatedTradingCalendarProvider provider =
      new SimulatedTradingCalendarProvider(CLOCK, Set.of());

  @Test
  void buildsStandardAshareSessionWindowsForTradingDay() {
    var day = provider.find("CN", FRIDAY).orElseThrow();

    assertThat(day.tradeDate()).isEqualTo(FRIDAY);
    assertThat(day.tradingDay()).isTrue();
    assertThat(day.windows()).containsExactly(
        window(TradingSession.PRE_OPEN, 0, 0, 9, 15),
        window(TradingSession.OPENING_CALL_AUCTION, 9, 15, 9, 25),
        window(TradingSession.PRE_OPEN, 9, 25, 9, 30),
        window(TradingSession.MORNING_CONTINUOUS, 9, 30, 11, 30),
        window(TradingSession.LUNCH_BREAK, 11, 30, 13, 0),
        window(TradingSession.AFTERNOON_CONTINUOUS, 13, 0, 14, 57),
        window(TradingSession.CLOSING_CALL_AUCTION, 14, 57, 15, 0));
  }

  @Test
  void weekendIsNotTradingDayAndCarriesNoWindows() {
    var day = provider.find("CN", SATURDAY).orElseThrow();

    assertThat(day.tradingDay()).isFalse();
    assertThat(day.windows()).isEmpty();
  }

  @Test
  void injectedHolidayIsNotTradingDay() {
    var withHoliday = new SimulatedTradingCalendarProvider(
        CLOCK, Set.of(LocalDate.of(2026, 10, 1)));

    var day = withHoliday.find("CN", LocalDate.of(2026, 10, 1)).orElseThrow();

    assertThat(day.tradingDay()).isFalse();
    assertThat(day.windows()).isEmpty();
  }

  @Test
  void parsesHolidaysFromCommaSeparatedConfiguration() {
    var fromCsv = SimulatedTradingCalendarProvider.ofCsv(CLOCK, " 2026-10-01 , 2026-10-02 ,, ");

    assertThat(fromCsv.find("CN", LocalDate.of(2026, 10, 1)).orElseThrow().tradingDay()).isFalse();
    assertThat(fromCsv.find("CN", LocalDate.of(2026, 10, 2)).orElseThrow().tradingDay()).isFalse();
    assertThat(fromCsv.find("CN", LocalDate.of(2026, 10, 9)).orElseThrow().tradingDay()).isTrue();
  }

  @Test
  void resolvesPreviousAndNextTradeDateAcrossWeekend() {
    var friday = provider.find("CN", FRIDAY).orElseThrow();
    assertThat(friday.previousTradeDate()).isEqualTo(LocalDate.of(2026, 9, 10));
    assertThat(friday.nextTradeDate()).isEqualTo(LocalDate.of(2026, 9, 14));

    var saturday = provider.find("CN", SATURDAY).orElseThrow();
    assertThat(saturday.previousTradeDate()).isEqualTo(FRIDAY);
    assertThat(saturday.nextTradeDate()).isEqualTo(LocalDate.of(2026, 9, 14));
  }

  @Test
  void acceptsMarketCodeCaseInsensitivelyAndRejectsUnknownMarket() {
    assertThat(provider.find("cn", FRIDAY)).isPresent();
    assertThat(provider.find("US", FRIDAY)).isEmpty();
  }

  @Test
  void sourceTimeReflectsClockReadingMoment() {
    var day = provider.find("CN", FRIDAY).orElseThrow();

    assertThat(day.sourceTime()).isEqualTo(OffsetDateTime.now(CLOCK));
  }

  private static TradingCalendarDay.Window window(
      TradingSession session, int startHour, int startMinute, int endHour, int endMinute) {
    return new TradingCalendarDay.Window(
        session, LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
  }
}
