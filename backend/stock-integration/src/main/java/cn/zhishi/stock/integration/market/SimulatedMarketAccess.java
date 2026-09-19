package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模拟源共享的取数辅助。
 *
 * <p>个股快照与 K 线都要先回答"这只证券是谁、它今天的行情是什么"。
 * 抽出来是为了让两者对这两个问题有**唯一答案**——各自实现的话，
 * 证券身份与前收价会在两条路径上悄悄分叉，而这不会有任何测试发现。
 */
final class SimulatedMarketAccess {

  private static final String SUPPORTED_MARKET = "CN";

  /** A 股行情时间固定在北京时间（与前端渲染口径一致）。 */
  private static final ZoneOffset MARKET_OFFSET = ZoneOffset.ofHours(8);

  private final SecurityQuoteProvider securityQuoteProvider;
  private final SecurityMasterProvider securityMasterProvider;
  private final TradingCalendarProvider tradingCalendarProvider;
  private final Clock clock;

  SimulatedMarketAccess(
      SecurityQuoteProvider securityQuoteProvider,
      SecurityMasterProvider securityMasterProvider,
      TradingCalendarProvider tradingCalendarProvider,
      Clock clock) {
    this.securityQuoteProvider = securityQuoteProvider;
    this.securityMasterProvider = securityMasterProvider;
    this.tradingCalendarProvider = tradingCalendarProvider;
    this.clock = clock;
  }

  static String marketCode() {
    return SUPPORTED_MARKET;
  }

  boolean supports(String marketCode) {
    return SUPPORTED_MARKET.equals(marketCode);
  }

  /** 最近的有效交易日：盘中为当日，盘后与节假日回退到上一交易日。 */
  LocalDate latestTradeDate() {
    LocalDate today = LocalDate.now(clock);
    return tradingCalendarProvider
        .find(SUPPORTED_MARKET, today)
        .map(day -> day.tradingDay() ? day.tradeDate() : day.previousTradeDate())
        .orElse(today);
  }

  Optional<SecurityQuote> quote(String securityId, LocalDate tradeDate) {
    return securityQuoteProvider.fetchUniverse(SUPPORTED_MARKET, tradeDate).stream()
        .filter(quote -> quote.securityId().equals(securityId))
        .findFirst();
  }

  Optional<SecuritySummary> summary(String securityId) {
    return securityMasterProvider.findAll(SUPPORTED_MARKET).stream()
        .filter(summary -> summary.securityId().equals(securityId))
        .findFirst();
  }

  LocalDate previousTradeDate(LocalDate date) {
    return tradingCalendarProvider
        .find(SUPPORTED_MARKET, date)
        .map(TradingCalendarDay::previousTradeDate)
        .orElse(date.minusDays(1));
  }

  /** 升序返回 {@code [start, end]} 内的全部交易日。 */
  List<LocalDate> tradingDaysBetween(LocalDate start, LocalDate end) {
    List<LocalDate> days = new ArrayList<>();
    LocalDate cursor = start;
    while (!cursor.isAfter(end)) {
      Optional<TradingCalendarDay> calendar =
          tradingCalendarProvider.find(SUPPORTED_MARKET, cursor);
      if (calendar.isEmpty()) {
        break;
      }
      TradingCalendarDay day = calendar.get();
      if (day.tradingDay()) {
        days.add(cursor);
      }
      LocalDate next = day.nextTradeDate();
      // nextTradeDate 异常返回自身时兜底前进一天，避免死循环
      cursor = next.isAfter(cursor) ? next : cursor.plusDays(1);
    }
    return List.copyOf(days);
  }

  /**
   * 某交易日的收盘时刻。
   *
   * <p>收盘时刻从交易日历取，不硬编码 15:00——硬编码会在遇到半日市或时段调整时静默出错。
   * 日历缺失时退化为当日零点，表示"时刻未知"，比伪造一个 15:00 诚实。
   */
  OffsetDateTime sessionEndAt(LocalDate tradeDate) {
    LocalTime close = tradingCalendarProvider
        .find(SUPPORTED_MARKET, tradeDate)
        .flatMap(TradingCalendarDay::lastSessionEnd)
        .orElse(LocalTime.MIDNIGHT);
    return OffsetDateTime.of(tradeDate, close, MARKET_OFFSET);
  }

  Clock clock() {
    return clock;
  }
}
