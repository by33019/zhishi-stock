package cn.zhishi.stock.integration.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性的模拟价格算法，供个股快照与 K 线**共用**。
 *
 * <p>抽成一个类而不是各写一遍，是因为两者必须对"这只证券今天开高低收是多少"
 * 给出同一个答案：各写一遍的话，改一处就会让行情头部与 K 线末端分叉，
 * 而且没有任何测试会红。
 *
 * <p>价格序列以最近交易日为锚点**向前倒推**（见 spec §4.2）：
 * <pre>
 *   close(锚点)     = 该证券的最新价
 *   close(锚点 - 1) = 该证券的前收价
 *   close(d)        = close(d + 1) / (1 + rate(d + 1))
 * </pre>
 * 前两根直接钉在行情源的取值上，因此"快照 ↔ 日 K 末端 ↔ 广度口径"三者天然一致；
 * 更早的价格按确定性日涨跌幅倒推，同一时刻查询的重叠区间逐点相同。
 *
 * <p>不使用 {@code Math.sin} / {@code Math.exp}：这些函数允许跨平台 1 ulp 差异，
 * 会让"确定性"名存实亡。全部改用整数运算 + SplitMix64 混合。
 */
public final class SimulatedPriceSeries {

  /** 单日涨跌幅上限，对应 A 股主板 10% 限幅下的日内常见波动。 */
  private static final BigDecimal MAX_DAILY_MOVE = new BigDecimal("0.03");

  private static final int PRICE_SCALE = 2;

  /** 中间计算精度：倒推会连乘数百次，精度太低会让误差累积到可见。 */
  private static final int WORKING_SCALE = 8;

  private static final long DAILY_MOVE_STEPS = 601L;

  private SimulatedPriceSeries() {
  }

  /**
   * 生成收盘价序列，末端锚定在行情源给出的最新价上。
   *
   * @param tradingDays 升序交易日，**最后一天是锚点日、倒数第二天是它的前一交易日**
   * @param anchorClose 锚点日收盘价，即该证券的最新价
   * @param previousClose 锚点日前一交易日的收盘价，即该证券的前收价
   * @return 与 {@code tradingDays} 等长的收盘价序列（已按展示精度取整）
   */
  public static List<BigDecimal> closesAnchoredAt(
      String securityId,
      List<LocalDate> tradingDays,
      BigDecimal anchorClose,
      BigDecimal previousClose) {
    int size = tradingDays.size();
    if (size == 0) {
      return List.of();
    }
    BigDecimal[] closes = new BigDecimal[size];
    closes[size - 1] = price(anchorClose);
    if (size >= 2) {
      closes[size - 2] = price(previousClose);
    }
    for (int index = size - 3; index >= 0; index--) {
      BigDecimal rate = dailyRate(securityId, tradingDays.get(index + 1));
      closes[index] = closes[index + 1]
          .divide(BigDecimal.ONE.add(rate), WORKING_SCALE, RoundingMode.HALF_UP)
          .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
    List<BigDecimal> result = new ArrayList<>(size);
    for (BigDecimal close : closes) {
      result.add(close);
    }
    return List.copyOf(result);
  }

  /**
   * 派生某一交易日的完整行情。
   *
   * <p>{@code high} / {@code low} 被夹在涨跌停价之内——否则会出现"K 线最高价
   * 超过涨停价"这种一眼假的数据，并与市场广度的涨跌停计数口径冲突。
   */
  public static DailyBar bar(
      String securityId,
      LocalDate tradeDate,
      BigDecimal previousClose,
      BigDecimal close,
      BigDecimal limitUp,
      BigDecimal limitDown) {
    BigDecimal open = open(securityId, tradeDate, previousClose, close, limitUp, limitDown);
    BigDecimal high = high(securityId, tradeDate, open, close, limitUp);
    BigDecimal low = low(securityId, tradeDate, open, close, limitDown);
    long volume = tradeVolume(securityId, tradeDate);
    BigDecimal average = high
        .add(low)
        .add(close)
        .divide(BigDecimal.valueOf(3), WORKING_SCALE, RoundingMode.HALF_UP);
    BigDecimal amount = average
        .multiply(BigDecimal.valueOf(volume))
        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    return new DailyBar(
        open,
        high,
        low,
        price(close),
        price(previousClose),
        volume,
        amount,
        turnoverRate(securityId, tradeDate));
  }

  /** 单个交易日的行情，是 K 线点与快照的共同来源。 */
  public record DailyBar(
      BigDecimal openPrice,
      BigDecimal highPrice,
      BigDecimal lowPrice,
      BigDecimal closePrice,
      BigDecimal previousClosePrice,
      long tradeVolume,
      BigDecimal tradeAmount,
      BigDecimal turnoverRate) {
  }

  /** 按展示精度输出为十进制定点字符串。 */
  public static String formatPrice(BigDecimal value) {
    return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * 涨跌幅用小数比例表示（{@code 0.10} 即 10%）。
   *
   * <p>前收价为 0 时返回 0 而不是抛异常：真实数据里这表示"当日无有效基准"，
   * 让一个字段的异常拖垮整个请求并不划算。
   */
  public static String formatChangeRate(BigDecimal changeAmount, BigDecimal previousClose) {
    if (previousClose == null || previousClose.signum() == 0) {
      return "0.0000";
    }
    return changeAmount
        .divide(previousClose, 6, RoundingMode.HALF_UP)
        .setScale(4, RoundingMode.HALF_UP)
        .toPlainString();
  }

  private static BigDecimal open(
      String securityId,
      LocalDate tradeDate,
      BigDecimal previousClose,
      BigDecimal close,
      BigDecimal limitUp,
      BigDecimal limitDown) {
    BigDecimal fraction = fraction(securityId, tradeDate, 11, 20, 61);
    BigDecimal candidate = previousClose
        .add(close.subtract(previousClose).multiply(fraction))
        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    return clamp(candidate, limitDown, limitUp);
  }

  private static BigDecimal high(
      String securityId, LocalDate tradeDate, BigDecimal open, BigDecimal close, BigDecimal limitUp) {
    BigDecimal base = open.max(close);
    BigDecimal headroom = limitUp.subtract(base);
    if (headroom.signum() <= 0) {
      return base;
    }
    BigDecimal fraction = fraction(securityId, tradeDate, 23, 0, 51);
    return base.add(headroom.multiply(fraction).setScale(PRICE_SCALE, RoundingMode.HALF_UP));
  }

  private static BigDecimal low(
      String securityId, LocalDate tradeDate, BigDecimal open, BigDecimal close, BigDecimal limitDown) {
    BigDecimal base = open.min(close);
    BigDecimal headroom = base.subtract(limitDown);
    if (headroom.signum() <= 0) {
      return base;
    }
    BigDecimal fraction = fraction(securityId, tradeDate, 37, 0, 51);
    return base.subtract(headroom.multiply(fraction).setScale(PRICE_SCALE, RoundingMode.HALF_UP));
  }

  private static long tradeVolume(String securityId, LocalDate tradeDate) {
    return 1_000_000L + Math.floorMod(hash(securityId, tradeDate, 53), 199_000_000L);
  }

  private static BigDecimal turnoverRate(String securityId, LocalDate tradeDate) {
    long bucket = Math.floorMod(hash(securityId, tradeDate, 71), 1491L);
    return BigDecimal.valueOf(10L + bucket).movePointLeft(4);
  }

  private static BigDecimal dailyRate(String securityId, LocalDate tradeDate) {
    long bucket = Math.floorMod(hash(securityId, tradeDate, 7), DAILY_MOVE_STEPS);
    return BigDecimal.valueOf(bucket - 300L).movePointLeft(4);
  }

  /** 取 {@code [offset/100, (offset + span)/100]} 区间内的确定性小数。 */
  private static BigDecimal fraction(
      String securityId, LocalDate tradeDate, int salt, int offset, long span) {
    long bucket = Math.floorMod(hash(securityId, tradeDate, salt), span);
    return BigDecimal.valueOf(offset + bucket).movePointLeft(2);
  }

  private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
    if (value.compareTo(min) < 0) {
      return min;
    }
    if (value.compareTo(max) > 0) {
      return max;
    }
    return value;
  }

  private static BigDecimal price(BigDecimal value) {
    return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private static long hash(String securityId, LocalDate tradeDate, int salt) {
    return mix(securityId.hashCode() * 1_000_003L + salt) ^ mix(tradeDate.toEpochDay() + salt);
  }

  /** SplitMix64 的收尾混合：把相邻的日期与相似的代码打散成互不相关的桶号。 */
  private static long mix(long value) {
    long z = value + 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
