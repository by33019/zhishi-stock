package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TradingSessions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模拟源共享的取数辅助。
 *
 * <p>个股快照、整批快照与 K 线都要先回答"这只证券是谁、它今天的行情是什么"。
 * 抽出来是为了让它们对这两个问题有**唯一答案**——各自实现的话，
 * 证券身份与前收价会在多条路径上悄悄分叉，而这不会有任何测试发现。
 */
final class SimulatedMarketAccess {

  private static final String SUPPORTED_MARKET = "CN";

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

  /**
   * 最近的有效交易日：盘中为当日，盘后与节假日回退到上一交易日。
   *
   * <p>规则本体在 {@link TradingSessions}——总览 Provider 也需要同一份口径，
   * 各写一遍的话两处会对"今天是哪一天"给出不同答案，而且不会有测试报错。
   */
  LocalDate latestTradeDate() {
    return TradingSessions.latestTradeDate(
        tradingCalendarProvider, SUPPORTED_MARKET, LocalDate.now(clock));
  }

  /** 指定交易日的一整批个股行情。整批装配只调一次，避免逐只查找退化成 O(n²)。 */
  List<SecurityQuote> universe(LocalDate tradeDate) {
    return securityQuoteProvider.fetchUniverse(SUPPORTED_MARKET, tradeDate);
  }

  Optional<SecurityQuote> quote(String securityId, LocalDate tradeDate) {
    return universe(tradeDate).stream()
        .filter(quote -> quote.securityId().equals(securityId))
        .findFirst();
  }

  Optional<SecuritySummary> summary(String securityId) {
    return securityMasterProvider.findAll(SUPPORTED_MARKET).stream()
        .filter(summary -> summary.securityId().equals(securityId))
        .findFirst();
  }

  /**
   * 主数据按 {@code securityId} 建索引。
   *
   * <p>整批装配要逐只取身份，线性查找会让 5149 只证券的装配退化成 2600 万次比较。
   * 用 {@code putIfAbsent} 而不是 {@code put}：主数据若出现重复 ID，保留先出现的那条，
   * 行为与 {@link #summary} 的 {@code findFirst} 一致。
   */
  Map<String, SecuritySummary> summaries() {
    Map<String, SecuritySummary> index = new HashMap<>();
    for (SecuritySummary summary : securityMasterProvider.findAll(SUPPORTED_MARKET)) {
      index.putIfAbsent(summary.securityId(), summary);
    }
    return index;
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
   * <p>日历缺失时退化为当日零点，表示"时刻未知"，比伪造一个 15:00 诚实。
   * 推导过程与总览快照共用 {@link SimulatedSessionTimes}，只有兜底策略不同。
   */
  OffsetDateTime sessionEndAt(LocalDate tradeDate) {
    return SimulatedSessionTimes.sessionEndAt(tradingCalendarProvider, SUPPORTED_MARKET, tradeDate)
        .orElse(OffsetDateTime.of(
            tradeDate, LocalTime.MIDNIGHT, SimulatedSessionTimes.MARKET_OFFSET));
  }

  Clock clock() {
    return clock;
  }
}
