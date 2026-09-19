package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.KlinePeriod;
import cn.zhishi.stock.market.domain.KlinePoint;
import cn.zhishi.stock.market.domain.KlineProvider;
import cn.zhishi.stock.market.domain.KlineQualityStatus;
import cn.zhishi.stock.market.domain.KlineRequest;
import cn.zhishi.stock.market.domain.KlineSeries;
import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性模拟日 / 周 / 月 K 线（STK-07）。
 *
 * <p><b>按需生成</b>：不预生成全市场历史（5149 只 × 5 年 ≈ 640 万点，内存里既存不下也不该存），
 * 而是给定证券与区间现算，单次请求只处理几百个点。
 *
 * <p><b>周 / 月 K 由日 K 聚合而来，不独立生成</b>——这样"日 K 序列"与"周 K 序列"
 * 永远自洽，不会出现同一段行情在两种周期下对不上的情况。
 *
 * <p>日 K 序列以最近交易日为锚点向前倒推（见 {@link SimulatedPriceSeries}），
 * 因此最后一根的收盘价恒等于该证券的最新价，倒数第二根恒等于它的前收价。
 */
public class SimulatedKlineProvider implements KlineProvider {

  private final SimulatedMarketAccess marketAccess;
  private final LimitRuleProvider limitRuleProvider;

  public SimulatedKlineProvider(
      SecurityQuoteProvider securityQuoteProvider,
      SecurityMasterProvider securityMasterProvider,
      LimitRuleProvider limitRuleProvider,
      TradingCalendarProvider tradingCalendarProvider,
      Clock clock) {
    this.marketAccess = new SimulatedMarketAccess(
        securityQuoteProvider, securityMasterProvider, tradingCalendarProvider, clock);
    this.limitRuleProvider = limitRuleProvider;
  }

  @Override
  public Optional<KlineSeries> fetch(KlineRequest request) {
    if (request == null || !marketAccess.supports(request.marketCode())) {
      return Optional.empty();
    }
    LocalDate anchorDate = marketAccess.latestTradeDate();
    Optional<SecuritySummary> summary = marketAccess.summary(request.securityId());
    Optional<SecurityQuote> quote = marketAccess.quote(request.securityId(), anchorDate);
    if (summary.isEmpty() || quote.isEmpty()) {
      return Optional.empty();
    }

    // 序列从区间首日的**前一交易日**开始：多算这一天，是为了让区间首日有真实的前收价
    List<LocalDate> days = marketAccess.tradingDaysBetween(
        marketAccess.previousTradeDate(request.startDate()), anchorDate);
    if (days.size() < 2) {
      return Optional.of(series(summary.get(), request, List.of()));
    }

    List<BigDecimal> closes = SimulatedPriceSeries.closesAnchoredAt(
        request.securityId(),
        days,
        quote.get().latestPrice(),
        quote.get().previousClosePrice());
    List<SimulatedPriceSeries.DailyBar> bars =
        dailyBars(request.securityId(), days, closes, quote.get());

    List<KlinePoint> points = request.period().aggregated()
        ? aggregate(request.period(), days, bars, request.endDate())
        : toPoints(days, bars, request.endDate());
    return Optional.of(series(summary.get(), request, points));
  }

  /**
   * 生成每个交易日的行情。
   *
   * <p>返回列表与 {@code days} 等长，但**下标 0 为 {@code null}**：
   * 那一天只是为了给区间首日提供前收价，本身不属于结果。
   */
  private List<SimulatedPriceSeries.DailyBar> dailyBars(
      String securityId, List<LocalDate> days, List<BigDecimal> closes, SecurityQuote quote) {
    List<SimulatedPriceSeries.DailyBar> bars = new ArrayList<>(days.size());
    bars.add(null);
    for (int index = 1; index < days.size(); index++) {
      LocalDate day = days.get(index);
      BigDecimal previousClose = closes.get(index - 1);
      BigDecimal close = closes.get(index);
      LimitRule rule = LimitRuleMatcher
          .match(
              limitRuleProvider.rules(SimulatedMarketAccess.marketCode(), day),
              quote,
              day)
          .orElse(null);
      BigDecimal limitUp = rule == null ? close.max(previousClose) : rule.limitUpPrice(previousClose);
      BigDecimal limitDown =
          rule == null ? close.min(previousClose) : rule.limitDownPrice(previousClose);
      bars.add(SimulatedPriceSeries.bar(
          securityId, day, previousClose, close, limitUp, limitDown));
    }
    return bars;
  }

  private static List<KlinePoint> toPoints(
      List<LocalDate> days, List<SimulatedPriceSeries.DailyBar> bars, LocalDate endDate) {
    List<KlinePoint> points = new ArrayList<>();
    for (int index = 1; index < days.size(); index++) {
      LocalDate day = days.get(index);
      if (day.isAfter(endDate)) {
        break;
      }
      points.add(point(day, bars.get(index)));
    }
    return List.copyOf(points);
  }

  private static List<KlinePoint> aggregate(
      KlinePeriod period,
      List<LocalDate> days,
      List<SimulatedPriceSeries.DailyBar> bars,
      LocalDate endDate) {
    Map<Object, List<Integer>> groups = new LinkedHashMap<>();
    for (int index = 1; index < days.size(); index++) {
      LocalDate day = days.get(index);
      if (day.isAfter(endDate)) {
        break;
      }
      groups.computeIfAbsent(bucketKey(period, day), key -> new ArrayList<>()).add(index);
    }
    List<KlinePoint> points = new ArrayList<>(groups.size());
    for (List<Integer> indices : groups.values()) {
      points.add(aggregatePoint(days, bars, indices));
    }
    return List.copyOf(points);
  }

  /**
   * 把一个周期内的日 K 合成一个点。
   *
   * <p>{@code time} 取该周期**最后一个交易日**（不是首日、也不是自然月末）：
   * 这样 {@code dataCutoffAt} 天然等于最后一个点的时刻，不会出现
   * "数据截止到 9/19、最后一点却标着 9/15"的矛盾。
   */
  private static KlinePoint aggregatePoint(
      List<LocalDate> days, List<SimulatedPriceSeries.DailyBar> bars, List<Integer> indices) {
    int firstIndex = indices.get(0);
    int lastIndex = indices.get(indices.size() - 1);
    SimulatedPriceSeries.DailyBar first = bars.get(firstIndex);
    SimulatedPriceSeries.DailyBar last = bars.get(lastIndex);
    BigDecimal high = indices.stream()
        .map(index -> bars.get(index).highPrice())
        .max(BigDecimal::compareTo)
        .orElseThrow();
    BigDecimal low = indices.stream()
        .map(index -> bars.get(index).lowPrice())
        .min(BigDecimal::compareTo)
        .orElseThrow();
    long volume = indices.stream().mapToLong(index -> bars.get(index).tradeVolume()).sum();
    BigDecimal amount = indices.stream()
        .map(index -> bars.get(index).tradeAmount())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal turnover = indices.stream()
        .map(index -> bars.get(index).turnoverRate())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return point(days.get(lastIndex), new SimulatedPriceSeries.DailyBar(
        first.openPrice(),
        high,
        low,
        last.closePrice(),
        first.previousClosePrice(),
        volume,
        amount,
        turnover));
  }

  private static KlinePoint point(LocalDate date, SimulatedPriceSeries.DailyBar bar) {
    BigDecimal changeAmount = bar.closePrice().subtract(bar.previousClosePrice());
    return new KlinePoint(
        date,
        SimulatedPriceSeries.formatPrice(bar.openPrice()),
        SimulatedPriceSeries.formatPrice(bar.highPrice()),
        SimulatedPriceSeries.formatPrice(bar.lowPrice()),
        SimulatedPriceSeries.formatPrice(bar.closePrice()),
        SimulatedPriceSeries.formatPrice(bar.previousClosePrice()),
        SimulatedPriceSeries.formatPrice(changeAmount),
        SimulatedPriceSeries.formatChangeRate(changeAmount, bar.previousClosePrice()),
        Long.toString(bar.tradeVolume()),
        SimulatedPriceSeries.formatPrice(bar.tradeAmount()),
        SimulatedPriceSeries.formatPrice(bar.turnoverRate()),
        KlineQualityStatus.VALID);
  }

  /** 分组键：周用 ISO 周（跨年周不会与上一年的同一周号混淆），月用年 + 月。 */
  private static Object bucketKey(KlinePeriod period, LocalDate date) {
    return switch (period) {
      case WEEK -> "W" + date.get(WeekFields.ISO.weekBasedYear())
          + "-" + date.get(WeekFields.ISO.weekOfWeekBasedYear());
      case MONTH -> "M" + date.getYear() + "-" + date.getMonthValue();
      case DAY -> date;
    };
  }

  private KlineSeries series(
      SecuritySummary summary, KlineRequest request, List<KlinePoint> points) {
    OffsetDateTime cutoff = points.isEmpty()
        ? null
        : marketAccess.sessionEndAt(points.get(points.size() - 1).time());
    return new KlineSeries(
        summary,
        request.period(),
        request.adjustment(),
        cutoff,
        MarketOverview.DataStatus.REALTIME,
        points);
  }
}
