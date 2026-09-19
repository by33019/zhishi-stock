package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TradingSession;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketStatusQueryServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  /** 2026-09-11 周五、2026-09-12 周六、2026-09-14 周一。 */
  private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);

  private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);
  private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 12);
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

  private static final OffsetDateTime SOURCE_TIME =
      OffsetDateTime.of(2026, 9, 11, 0, 0, 0, 0, ZoneOffset.ofHours(8));

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

  @Test
  void reportsPreOpenBeforeOpeningCallAuction() {
    var status = serviceAt(FRIDAY, LocalTime.of(8, 0)).getStatus("CN", null);

    assertThat(status.marketCode()).isEqualTo("CN");
    assertThat(status.tradeDate()).isEqualTo(FRIDAY);
    assertThat(status.tradingDay()).isTrue();
    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.PRE_OPEN);
    assertThat(status.currentSession()).isEqualTo(TradingSession.PRE_OPEN);
    assertThat(status.nextSessionAt()).isEqualTo(at(FRIDAY, LocalTime.of(9, 15)));
    assertThat(status.calendarSourceTime()).isEqualTo(SOURCE_TIME);
  }

  @Test
  void reportsOpeningCallAuctionBetween0915And0925() {
    var status = serviceAt(FRIDAY, LocalTime.of(9, 20)).getStatus("CN", null);

    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.CALL_AUCTION);
    assertThat(status.currentSession()).isEqualTo(TradingSession.OPENING_CALL_AUCTION);
    assertThat(status.nextSessionAt()).isEqualTo(at(FRIDAY, LocalTime.of(9, 30)));
  }

  @Test
  void reportsQuietWindowBetweenOpeningAuctionAndContinuousTrading() {
    var status = serviceAt(FRIDAY, LocalTime.of(9, 27)).getStatus("CN", null);

    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.PRE_OPEN);
    assertThat(status.currentSession()).isEqualTo(TradingSession.PRE_OPEN);
    assertThat(status.nextSessionAt()).isEqualTo(at(FRIDAY, LocalTime.of(9, 30)));
  }

  @Test
  void reportsMorningContinuousTrading() {
    var status = serviceAt(FRIDAY, LocalTime.of(10, 0)).getStatus("CN", null);

    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.TRADING);
    assertThat(status.currentSession()).isEqualTo(TradingSession.MORNING_CONTINUOUS);
    assertThat(status.nextSessionAt()).isEqualTo(at(FRIDAY, LocalTime.of(11, 30)));
  }

  @Test
  void reportsLunchBreak() {
    var status = serviceAt(FRIDAY, LocalTime.of(12, 0)).getStatus("CN", null);

    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.BREAK);
    assertThat(status.currentSession()).isEqualTo(TradingSession.LUNCH_BREAK);
    assertThat(status.nextSessionAt()).isEqualTo(at(FRIDAY, LocalTime.of(13, 0)));
  }

  @Test
  void reportsAfternoonContinuousTrading() {
    var status = serviceAt(FRIDAY, LocalTime.of(14, 0)).getStatus("CN", null);

    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.TRADING);
    assertThat(status.currentSession()).isEqualTo(TradingSession.AFTERNOON_CONTINUOUS);
    assertThat(status.nextSessionAt()).isEqualTo(at(FRIDAY, LocalTime.of(14, 57)));
  }

  @Test
  void reportsClosingCallAuctionAndRollsNextSessionToNextTradingDay() {
    var status = serviceAt(FRIDAY, LocalTime.of(14, 58)).getStatus("CN", null);

    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.CALL_AUCTION);
    assertThat(status.currentSession()).isEqualTo(TradingSession.CLOSING_CALL_AUCTION);
    assertThat(status.nextSessionAt()).isEqualTo(at(MONDAY, LocalTime.of(9, 15)));
  }

  @Test
  void reportsClosedAfterLastSession() {
    var status = serviceAt(FRIDAY, LocalTime.of(16, 0)).getStatus("CN", null);

    assertThat(status.tradingDay()).isTrue();
    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    assertThat(status.currentSession()).isEqualTo(TradingSession.CLOSED);
    assertThat(status.nextSessionAt()).isEqualTo(at(MONDAY, LocalTime.of(9, 15)));
  }

  @Test
  void reportsNonTradingDayAsClosed() {
    var status = serviceAt(SATURDAY, LocalTime.of(10, 0)).getStatus("CN", null);

    assertThat(status.tradeDate()).isEqualTo(SATURDAY);
    assertThat(status.tradingDay()).isFalse();
    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    assertThat(status.currentSession()).isEqualTo(TradingSession.CLOSED);
    assertThat(status.nextSessionAt()).isEqualTo(at(MONDAY, LocalTime.of(9, 15)));
  }

  @Test
  void treatsSessionBoundariesAsLeftClosedRightOpen() {
    assertThat(serviceAt(FRIDAY, LocalTime.of(9, 15)).getStatus("CN", null).currentSession())
        .isEqualTo(TradingSession.OPENING_CALL_AUCTION);
    assertThat(serviceAt(FRIDAY, LocalTime.of(9, 25)).getStatus("CN", null).currentSession())
        .isEqualTo(TradingSession.PRE_OPEN);
    assertThat(serviceAt(FRIDAY, LocalTime.of(11, 30)).getStatus("CN", null).currentSession())
        .isEqualTo(TradingSession.LUNCH_BREAK);
    assertThat(serviceAt(FRIDAY, LocalTime.of(13, 0)).getStatus("CN", null).currentSession())
        .isEqualTo(TradingSession.AFTERNOON_CONTINUOUS);
    assertThat(serviceAt(FRIDAY, LocalTime.of(15, 0)).getStatus("CN", null).currentSession())
        .isEqualTo(TradingSession.CLOSED);
  }

  @Test
  void historicalDateIsAlwaysClosedAndHasNoNextSession() {
    var status = serviceAt(FRIDAY, LocalTime.of(10, 0)).getStatus("CN", THURSDAY);

    assertThat(status.tradeDate()).isEqualTo(THURSDAY);
    assertThat(status.tradingDay()).isTrue();
    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    assertThat(status.currentSession()).isEqualTo(TradingSession.CLOSED);
    assertThat(status.nextSessionAt()).isNull();
  }

  @Test
  void futureDateIsAlwaysClosedAndHasNoNextSession() {
    var status = serviceAt(FRIDAY, LocalTime.of(10, 0)).getStatus("CN", MONDAY);

    assertThat(status.tradeDate()).isEqualTo(MONDAY);
    assertThat(status.tradingDay()).isTrue();
    assertThat(status.sessionStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    assertThat(status.nextSessionAt()).isNull();
  }

  @Test
  void normalizesMarketCodeToUpperCase() {
    var status = serviceAt(FRIDAY, LocalTime.of(10, 0)).getStatus("cn", null);

    assertThat(status.marketCode()).isEqualTo("CN");
  }

  @Test
  void rejectsUnsupportedMarketCode() {
    assertThatThrownBy(() -> serviceAt(FRIDAY, LocalTime.of(10, 0)).getStatus("US", null))
        .isInstanceOf(MarketNotFoundException.class)
        .hasMessageContaining("US");
  }

  @Test
  void returnsNullNextSessionWhenCalendarHasNoFutureTradingDay() {
    TradingCalendarProvider finite = (marketCode, date) -> Optional.of(new TradingCalendarDay(
        date, true, date.minusDays(1), null, WINDOWS, SOURCE_TIME));
    var service = new MarketStatusQueryService(finite, clockAt(FRIDAY, LocalTime.of(16, 0)));

    assertThat(service.getStatus("CN", null).nextSessionAt()).isNull();
  }

  @Test
  void returnsNullNextSessionWhenCalendarHasNoWindows() {
    TradingCalendarProvider emptyWindows = (marketCode, date) -> Optional.of(
        new TradingCalendarDay(date, true, date.minusDays(1), date.plusDays(1), List.of(), SOURCE_TIME));
    var service = new MarketStatusQueryService(emptyWindows, clockAt(FRIDAY, LocalTime.of(10, 0)));

    assertThat(service.getStatus("CN", null).nextSessionAt()).isNull();
  }

  private static MarketStatusQueryService serviceAt(LocalDate date, LocalTime time) {
    return new MarketStatusQueryService(calendar(), clockAt(date, time));
  }

  private static Clock clockAt(LocalDate date, LocalTime time) {
    return Clock.fixed(date.atTime(time).atZone(ZONE).toInstant(), ZONE);
  }

  private static OffsetDateTime at(LocalDate date, LocalTime time) {
    return date.atTime(time).atZone(ZONE).toOffsetDateTime();
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
          SOURCE_TIME));
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
