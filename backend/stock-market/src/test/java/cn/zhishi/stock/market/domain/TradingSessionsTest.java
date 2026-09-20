package cn.zhishi.stock.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TradingSessionsTest {

  /** 2026-09-11 周五、2026-09-12 周六、2026-09-14 周一。 */
  private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);

  private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);
  private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 13);
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

  private static final List<TradingCalendarDay.Window> WINDOWS = List.of(
      new TradingCalendarDay.Window(TradingSession.PRE_OPEN, LocalTime.of(0, 0), LocalTime.of(9, 15)),
      new TradingCalendarDay.Window(
          TradingSession.OPENING_CALL_AUCTION, LocalTime.of(9, 15), LocalTime.of(9, 25)),
      new TradingCalendarDay.Window(TradingSession.PRE_OPEN, LocalTime.of(9, 25), LocalTime.of(9, 30)),
      new TradingCalendarDay.Window(
          TradingSession.MORNING_CONTINUOUS, LocalTime.of(9, 30), LocalTime.of(11, 30)),
      new TradingCalendarDay.Window(
          TradingSession.LUNCH_BREAK, LocalTime.of(11, 30), LocalTime.of(13, 0)),
      new TradingCalendarDay.Window(
          TradingSession.AFTERNOON_CONTINUOUS, LocalTime.of(13, 0), LocalTime.of(14, 57)),
      new TradingCalendarDay.Window(
          TradingSession.CLOSING_CALL_AUCTION, LocalTime.of(14, 57), LocalTime.of(15, 0)));

  // ---------- latestTradeDate ----------

  @Test
  void keepsTodayWhenTodayIsATradingDay() {
    assertThat(TradingSessions.latestTradeDate(calendar(), "CN", FRIDAY)).isEqualTo(FRIDAY);
  }

  /**
   * 这条是本切片的核心：非交易日必须回退到上一交易日。
   *
   * <p>修复前总览 Provider 直接用 {@code now.toLocalDate()}，于是周日的快照里
   * {@code tradeDate} 是周日，而同一份快照的榜单来自周五的批次。
   */
  @Test
  void fallsBackToPreviousTradingDayOnWeekend() {
    assertThat(TradingSessions.latestTradeDate(calendar(), "CN", SATURDAY)).isEqualTo(FRIDAY);
    assertThat(TradingSessions.latestTradeDate(calendar(), "CN", SUNDAY)).isEqualTo(FRIDAY);
  }

  @Test
  void returnsTodayWhenCalendarDoesNotKnowTheMarket() {
    assertThat(TradingSessions.latestTradeDate(calendar(), "US", FRIDAY)).isEqualTo(FRIDAY);
  }

  /** 日历既说"非交易日"又没给出上一交易日时，宁可退回今天，也不让 null 渗进响应体。 */
  @Test
  void returnsTodayWhenNonTradingDayHasNoPreviousTradeDate() {
    TradingCalendarProvider noPrevious = (marketCode, date) -> Optional.of(
        new TradingCalendarDay(date, false, null, null, List.of(), null));

    assertThat(TradingSessions.latestTradeDate(noPrevious, "CN", SATURDAY)).isEqualTo(SATURDAY);
  }

  // ---------- currentSession ----------

  @Test
  void reportsTheSessionCoveringNowOnATradingDay() {
    assertThat(currentSessionAt(FRIDAY, LocalTime.of(8, 0))).isEqualTo(TradingSession.PRE_OPEN);
    assertThat(currentSessionAt(FRIDAY, LocalTime.of(9, 20)))
        .isEqualTo(TradingSession.OPENING_CALL_AUCTION);
    assertThat(currentSessionAt(FRIDAY, LocalTime.of(10, 0)))
        .isEqualTo(TradingSession.MORNING_CONTINUOUS);
    assertThat(currentSessionAt(FRIDAY, LocalTime.of(12, 0))).isEqualTo(TradingSession.LUNCH_BREAK);
    assertThat(currentSessionAt(FRIDAY, LocalTime.of(14, 0)))
        .isEqualTo(TradingSession.AFTERNOON_CONTINUOUS);
  }

  /** 收盘后没有任何窗口覆盖，兜底为 CLOSED——与 MKT-02 的既有口径一致。 */
  @Test
  void reportsClosedAfterTheLastSession() {
    assertThat(currentSessionAt(FRIDAY, LocalTime.of(16, 0))).isEqualTo(TradingSession.CLOSED);
    assertThat(currentSessionAt(FRIDAY, LocalTime.of(23, 59))).isEqualTo(TradingSession.CLOSED);
  }

  @Test
  void reportsClosedOnANonTradingDayEvenDuringSessionHours() {
    assertThat(currentSessionAt(SATURDAY, LocalTime.of(10, 0))).isEqualTo(TradingSession.CLOSED);
  }

  /** 目标日期不是"今天"时，盘中状态无法由当前时刻还原，一律 CLOSED。 */
  @Test
  void reportsClosedForADayThatIsNotToday() {
    TradingCalendarDay monday = calendar().find("CN", MONDAY).orElseThrow();

    assertThat(TradingSessions.currentSession(monday, FRIDAY, LocalTime.of(10, 0)))
        .isEqualTo(TradingSession.CLOSED);
  }

  private static TradingSession currentSessionAt(LocalDate day, LocalTime time) {
    return TradingSessions.currentSession(calendar().find("CN", day).orElseThrow(), day, time);
  }

  private static TradingCalendarProvider calendar() {
    return (marketCode, date) -> {
      if (!"CN".equals(marketCode)) {
        return Optional.empty();
      }
      boolean trading = date.getDayOfWeek().getValue() <= 5;
      return Optional.of(new TradingCalendarDay(
          date,
          trading,
          previousTradeDate(date),
          nextTradeDate(date),
          trading ? WINDOWS : List.of(),
          null));
    };
  }

  private static LocalDate previousTradeDate(LocalDate date) {
    LocalDate candidate = date.minusDays(1);
    while (candidate.getDayOfWeek().getValue() > 5) {
      candidate = candidate.minusDays(1);
    }
    return candidate;
  }

  private static LocalDate nextTradeDate(LocalDate date) {
    LocalDate candidate = date.plusDays(1);
    while (candidate.getDayOfWeek().getValue() > 5) {
      candidate = candidate.plusDays(1);
    }
    return candidate;
  }
}
