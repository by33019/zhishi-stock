package cn.zhishi.stock.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 板块统计口径（SEC-02 / SEC-03 / SEC-04 的共同基础）。
 *
 * <p>这些断言就是契约里"均价 / 涨跌幅 / 公司数 / 成交量额 / 领涨股"的口径定义本身：
 * 把口径写在纯函数上，SEC-02、SEC-03、SEC-04 三处就无法各自演化出
 * "均价算不算停牌股"这种差异。
 */
class SectorQuoteCalculatorTest {

  private static final Sector SECTOR = new Sector(
      "sim-bk0026", "BK0026", "银行", "INDUSTRY", "sim-bk0021", 2, "ACTIVE");

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.of(LocalDate.of(2026, 9, 18), LocalTime.of(15, 0), ZoneOffset.ofHours(8));

  @Test
  void countsSuspendedConstituentsButExcludesThemFromStatistics() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR,
        List.of(
            snapshot("sim-600001", "10.00", "0.1000", "1000000", "10000000.00", false),
            snapshot("sim-600002", "20.00", "0.0200", "2000000", "20000000.00", false),
            // 停牌：最新价等于前收、涨跌幅为 0，但**不得**混进均价与涨跌幅
            snapshot("sim-600003", "999.00", "0.0000", "3000000", "30000000.00", true)),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME);

    assertThat(quote.companyCount()).isEqualTo(3);
    assertThat(quote.averagePrice()).isEqualTo("15.00");
    assertThat(quote.changeRate()).isEqualTo("0.0600");
    assertThat(quote.tradeVolume()).isEqualTo("3000000");
    assertThat(quote.tradeAmount()).isEqualTo("30000000.00");
  }

  @Test
  void picksLeadingAndLaggingStockAmongQuotedConstituents() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR,
        List.of(
            snapshot("sim-600001", "10.00", "0.0300", "1000000", "10000000.00", false),
            snapshot("sim-600002", "20.00", "-0.0400", "2000000", "20000000.00", false),
            snapshot("sim-600003", "30.00", "0.0800", "3000000", "30000000.00", false)),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME);

    assertThat(quote.leadingStock().security().securityId()).isEqualTo("sim-600003");
    assertThat(quote.leadingStock().changeRate()).isEqualTo("0.0800");
    assertThat(quote.leadingStock().latestPrice()).isEqualTo("30.00");
    assertThat(quote.laggingStock().security().securityId()).isEqualTo("sim-600002");
    assertThat(quote.laggingStock().changeRate()).isEqualTo("-0.0400");
  }

  /** 同涨幅时必须给出唯一且与遍历顺序无关的结果，否则"领涨股"会随数据顺序漂移。 */
  @Test
  void breaksLeadingStockTieByFullSymbolAscending() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR,
        List.of(
            snapshot("sim-600009", "10.00", "0.0500", "1000000", "10000000.00", false),
            snapshot("sim-600001", "20.00", "0.0500", "2000000", "20000000.00", false)),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME);

    assertThat(quote.leadingStock().security().fullSymbol()).isEqualTo("SH.600001");
    assertThat(quote.laggingStock().security().fullSymbol()).isEqualTo("SH.600001");
  }

  /**
   * 全部成分股停牌时"平均涨跌幅"没有定义。
   *
   * <p>补 {@code 0} 会被读成"板块平盘"——PRD §7.4 SEC-02 明确「历史断点不得补 0」。
   */
  @Test
  void returnsNullStatisticsWhenNoConstituentHasValidQuote() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR,
        List.of(
            snapshot("sim-600001", "10.00", "0.0000", "1000000", "10000000.00", true),
            snapshot("sim-600002", "20.00", "0.0000", "2000000", "20000000.00", true)),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME);

    assertThat(quote.companyCount()).isEqualTo(2);
    assertThat(quote.averagePrice()).isNull();
    assertThat(quote.changeRate()).isNull();
    assertThat(quote.leadingStock()).isNull();
    assertThat(quote.laggingStock()).isNull();
    assertThat(quote.tradeVolume()).isEqualTo("0");
    assertThat(quote.tradeAmount()).isEqualTo("0.00");
  }

  @Test
  void returnsZeroedStatisticsForEmptyConstituents() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR, List.of(), DATA_TIME, MarketOverview.DataStatus.REALTIME);

    assertThat(quote.companyCount()).isZero();
    assertThat(quote.averagePrice()).isNull();
    assertThat(quote.changeRate()).isNull();
    assertThat(quote.tradeVolume()).isEqualTo("0");
    assertThat(quote.tradeAmount()).isEqualTo("0.00");
    assertThat(quote.sectorId()).isEqualTo("sim-bk0026");
    assertThat(quote.sectorType()).isEqualTo("INDUSTRY");
    assertThat(quote.dataTime()).isEqualTo(DATA_TIME);
    assertThat(quote.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
  }

  /**
   * 涨跌幅是十进制定点字符串，必须解析成 {@code BigDecimal} 后比较。
   *
   * <p>按字典序比较是错的：{@code "0.10"} 的字典序小于 {@code "0.0218"}，数值上却更大。
   */
  @Test
  void comparesChangeRateNumericallyNotLexicographically() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR,
        List.of(
            snapshot("sim-600001", "10.00", "0.0218", "1000000", "10000000.00", false),
            snapshot("sim-600002", "20.00", "0.1000", "2000000", "20000000.00", false)),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME);

    assertThat(quote.leadingStock().security().securityId()).isEqualTo("sim-600002");
    assertThat(quote.laggingStock().security().securityId()).isEqualTo("sim-600001");
  }

  /** 缺少有效涨跌幅的成分股不参与统计，也不能让整个板块统计崩掉。 */
  @Test
  void skipsConstituentsWithoutValidChangeRate() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR,
        List.of(
            snapshot("sim-600001", "10.00", "0.0200", "1000000", "10000000.00", false),
            new QuoteSnapshot(
                summary("sim-600002", false),
                "20.00", "20.00", "20.00", "20.00", "20.00", "0.00", null,
                "2000000", "20000000.00", "0.0100",
                DATA_TIME, DATA_TIME, "sim-2026-09-18", MarketOverview.DataStatus.REALTIME, null)),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME);

    assertThat(quote.companyCount()).isEqualTo(2);
    assertThat(quote.changeRate()).isEqualTo("0.0200");
    assertThat(quote.averagePrice()).isEqualTo("10.00");
    assertThat(quote.leadingStock().security().securityId()).isEqualTo("sim-600001");
  }

  /** 成交量额只累加有成交的成分股；停牌股在模拟源里仍带非零成交量，不得计入。 */
  @Test
  void sumsVolumeAndAmountUsingBigDecimal() {
    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR,
        List.of(
            snapshot("sim-600001", "10.00", "0.0100", "199000000", "9999999999.99", false),
            snapshot("sim-600002", "20.00", "0.0100", "199000000", "9999999999.99", false)),
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME);

    assertThat(quote.tradeVolume()).isEqualTo("398000000");
    assertThat(quote.tradeAmount()).isEqualTo("19999999999.98");
  }

  /**
   * 贡献度排名：有有效涨跌幅的按涨跌幅降序在前，无有效价格者按代码升序续号。
   *
   * <p>排名第 1 必须与 {@code leadingStock} 指向同一只证券——两者共用同一个比较器，
   * 是构造保证而不是约定。
   */
  @Test
  void ranksContributionsByChangeRateDescending() {
    List<QuoteSnapshot> constituents = List.of(
        snapshot("sim-600001", "10.00", "0.0300", "1000000", "10000000.00", false),
        snapshot("sim-600002", "20.00", "0.0800", "2000000", "20000000.00", false),
        snapshot("sim-600003", "30.00", "-0.0100", "3000000", "30000000.00", false),
        // 停牌：无有效价格，排在最后
        snapshot("sim-600004", "40.00", "0.0000", "4000000", "40000000.00", true));

    var ranks = SectorQuoteCalculator.contributionRanks(constituents);

    assertThat(ranks.get("sim-600002")).isEqualTo(1);
    assertThat(ranks.get("sim-600001")).isEqualTo(2);
    assertThat(ranks.get("sim-600003")).isEqualTo(3);
    assertThat(ranks.get("sim-600004")).isEqualTo(4);

    SectorQuote quote = SectorQuoteCalculator.calculate(
        SECTOR, constituents, DATA_TIME, MarketOverview.DataStatus.REALTIME);
    assertThat(quote.leadingStock().security().securityId()).isEqualTo("sim-600002");
  }

  /** 同涨跌幅的贡献度排名必须唯一且与遍历顺序无关。 */
  @Test
  void breaksContributionRankTieByFullSymbolAscending() {
    var ranks = SectorQuoteCalculator.contributionRanks(List.of(
        snapshot("sim-600009", "10.00", "0.0500", "1000000", "10000000.00", false),
        snapshot("sim-600001", "20.00", "0.0500", "2000000", "20000000.00", false)));

    assertThat(ranks.get("sim-600001")).isEqualTo(1);
    assertThat(ranks.get("sim-600009")).isEqualTo(2);
  }

  /** 无有效涨跌幅的排在最后，且它们之间按代码升序——否则"缺失"会挤占领涨位置。 */
  @Test
  void placesConstituentsWithoutChangeRateLastOrderedBySymbol() {
    var ranks = SectorQuoteCalculator.contributionRanks(List.of(
        snapshot("sim-600005", "10.00", "0.0100", "1000000", "10000000.00", false),
        new QuoteSnapshot(
            summary("sim-600008", true),
            "20.00", "20.00", "20.00", "20.00", "20.00", "0.00", "0.0000",
            "2000000", "20000000.00", "0.0100",
            DATA_TIME, DATA_TIME, "sim-2026-09-18", MarketOverview.DataStatus.REALTIME, null),
        new QuoteSnapshot(
            summary("sim-600002", true),
            "30.00", "30.00", "30.00", "30.00", "30.00", "0.00", "0.0000",
            "3000000", "30000000.00", "0.0100",
            DATA_TIME, DATA_TIME, "sim-2026-09-18", MarketOverview.DataStatus.REALTIME, null)));

    assertThat(ranks.get("sim-600005")).isEqualTo(1);
    assertThat(ranks.get("sim-600002")).isEqualTo(2);
    assertThat(ranks.get("sim-600008")).isEqualTo(3);
  }

  private static QuoteSnapshot snapshot(
      String securityId,
      String latestPrice,
      String changeRate,
      String tradeVolume,
      String tradeAmount,
      boolean suspended) {
    return new QuoteSnapshot(
        summary(securityId, suspended),
        "10.00", "10.00", latestPrice, "10.00", "10.00", "0.00", changeRate,
        tradeVolume, tradeAmount, "0.0100",
        DATA_TIME, DATA_TIME, "sim-2026-09-18", MarketOverview.DataStatus.REALTIME, null);
  }

  private static SecuritySummary summary(String securityId, boolean suspended) {
    String code = securityId.substring(securityId.indexOf('-') + 1);
    return new SecuritySummary(
        securityId, "SH." + code, code, "模拟证券" + code,
        "SH", "STOCK", "MAIN", suspended ? "SUSPENDED" : "LISTED", false, suspended, 2, null, null);
  }
}
