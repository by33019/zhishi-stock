package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.IntradayPoint;
import cn.zhishi.stock.market.domain.IntradayProvider;
import cn.zhishi.stock.market.domain.IntradayRequest;
import cn.zhishi.stock.market.domain.IntradaySeries;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.SplittableRandom;

/**
 * 分时序列的确定性模拟实现（STK-06）。
 *
 * <h2>生成规则</h2>
 * A 股两个交易时段（09:30–11:30、13:00–15:00）共 240 个一分钟槽位；
 * 以 {@code hash(securityId, tradeDate)} 做种子、以当日昨收为锚的随机游走
 * 生成每分钟收盘价（午休后价格从上午收盘**连续**延续），再按请求粒度聚合成
 * OHLCV 桶。游走被硬性夹在昨收 ±10% 内（涨跌停近似）——确定性模拟不追求
 * 复刻真实的分钟级行情，只追求"同请求同结果、形状合法、只含已闭合分钟"。
 *
 * <h2>跨午休的分桶</h2>
 * 聚合只在**时段内部**进行（午休不与上下午混桶）：60 分钟粒度恰好每个时段
 * 各两根；1 分钟粒度是 240 根。
 */
public class SimulatedIntradayProvider implements IntradayProvider {

  private static final ZoneId DATA_ZONE = ZoneId.of("Asia/Shanghai");
  private static final LocalTime MORNING_OPEN = LocalTime.of(9, 30);
  private static final LocalTime MORNING_CLOSE = LocalTime.of(11, 30);
  private static final LocalTime AFTERNOON_OPEN = LocalTime.of(13, 0);
  private static final LocalTime AFTERNOON_CLOSE = LocalTime.of(15, 0);
  /** 涨跌停近似：确定性游走的硬边界（真实涨跌停价按前收 ±10%）。 */
  private static final BigDecimal WALK_BOUND = new BigDecimal("0.10");
  private static final BigDecimal STEP_BOUND = new BigDecimal("0.002");

  private final SimulatedMarketAccess marketAccess;

  public SimulatedIntradayProvider(
      SecurityQuoteProvider securityQuoteProvider,
      SecurityMasterProvider securityMasterProvider,
      TradingCalendarProvider tradingCalendarProvider,
      java.time.Clock clock) {
    this.marketAccess = new SimulatedMarketAccess(
        securityQuoteProvider, securityMasterProvider, tradingCalendarProvider, clock);
  }

  @Override
  public Optional<IntradaySeries> fetch(IntradayRequest request) {
    if (request == null || !marketAccess.supports(request.marketCode())) {
      return Optional.empty();
    }
    Optional<SecurityQuote> quote =
        marketAccess.quote(request.securityId(), request.tradeDate());
    Optional<SecuritySummary> summary = marketAccess.summary(request.securityId());
    if (summary.isEmpty() || quote.isEmpty()) {
      return Optional.empty();
    }

    List<IntradayPoint> points = generate(request, quote.get());
    OffsetDateTime cutoff = LocalDateTime.of(request.tradeDate(), AFTERNOON_CLOSE)
        .atZone(DATA_ZONE).toOffsetDateTime();
    return Optional.of(new IntradaySeries(
        summary.get(),
        request.tradeDate(),
        request.intervalMinutes(),
        formatPrice(quote.get().previousClosePrice()),
        cutoff,
        MarketOverview.DataStatus.REALTIME,
        points));
  }

  private List<IntradayPoint> generate(IntradayRequest request, SecurityQuote quote) {
    long seed = request.securityId().hashCode() * 31L + request.tradeDate().toEpochDay();
    SplittableRandom random = new SplittableRandom(seed);

    BigDecimal previousClose = quote.previousClosePrice();
    BigDecimal floor = previousClose.multiply(BigDecimal.ONE.subtract(WALK_BOUND));
    BigDecimal ceiling = previousClose.multiply(BigDecimal.ONE.add(WALK_BOUND));
    BigDecimal price = quote.latestPrice() != null ? quote.latestPrice() : previousClose;

    List<IntradayPoint> oneMinute = new ArrayList<>(240);
    price = walkSession(oneMinute, random, request.tradeDate(),
        MORNING_OPEN, price, previousClose, floor, ceiling);
    walkSession(oneMinute, random, request.tradeDate(),
        AFTERNOON_OPEN, price, previousClose, floor, ceiling);

    return aggregate(oneMinute, request.intervalMinutes());
  }

  /** 确定性游走一个时段，返回时段收盘价（下午时段由此延续上午的价格）。 */
  private BigDecimal walkSession(
      List<IntradayPoint> out, SplittableRandom random, LocalDate tradeDate, LocalTime sessionOpen,
      BigDecimal startPrice, BigDecimal previousClose, BigDecimal floor, BigDecimal ceiling) {
    BigDecimal price = startPrice;
    for (int minute = 0; minute < 120; minute++) {
      BigDecimal open = price;
      BigDecimal step = previousClose
          .multiply(STEP_BOUND)
          .multiply(BigDecimal.valueOf(random.nextDouble() * 2 - 1));
      price = open.add(step).max(floor).min(ceiling)
          .setScale(2, RoundingMode.HALF_UP);
      BigDecimal high = open.max(price);
      BigDecimal low = open.min(price);
      long volume = 100L * (50 + random.nextInt(950));
      out.add(new IntradayPoint(
          LocalDateTime.of(tradeDate, sessionOpen.plusMinutes(minute)),
          formatPrice(open), formatPrice(high), formatPrice(low), formatPrice(price),
          Long.toString(volume), formatAmount(volume, price)));
    }
    return price;
  }

  /** 按粒度把一分钟点聚合成桶；桶边界在时段内部对齐到时段开盘时刻。 */
  private List<IntradayPoint> aggregate(
      List<IntradayPoint> oneMinute, int intervalMinutes) {
    List<IntradayPoint> buckets = new ArrayList<>();
    for (int start = 0; start < oneMinute.size(); start += intervalMinutes) {
      List<IntradayPoint> bucket = oneMinute.subList(
          start, Math.min(start + intervalMinutes, oneMinute.size()));
      if (bucket.isEmpty()) {
        continue;
      }
      BigDecimal high = BigDecimal.ZERO;
      BigDecimal low = null;
      long volume = 0;
      BigDecimal amount = BigDecimal.ZERO;
      for (IntradayPoint point : bucket) {
        high = high.max(new BigDecimal(point.highPrice()));
        low = low == null ? new BigDecimal(point.lowPrice()) : low.min(new BigDecimal(point.lowPrice()));
        volume += Long.parseLong(point.tradeVolume());
        amount = amount.add(new BigDecimal(point.tradeAmount()));
      }
      buckets.add(new IntradayPoint(
          bucket.get(0).time(),
          bucket.get(0).openPrice(),
          formatPrice(high),
          formatPrice(low),
          bucket.get(bucket.size() - 1).closePrice(),
          Long.toString(volume),
          amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
    }
    return buckets;
  }

  private static String formatPrice(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String formatAmount(long volumeShares, BigDecimal price) {
    return BigDecimal.valueOf(volumeShares).multiply(price)
        .setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
