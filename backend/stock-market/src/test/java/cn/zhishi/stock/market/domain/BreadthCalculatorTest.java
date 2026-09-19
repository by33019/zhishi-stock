package cn.zhishi.stock.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.MarketOverview.BreadthData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BreadthCalculatorTest {

  private static final LocalDate RULE_DATE = LocalDate.of(2026, 9, 11);

  private static final List<LimitRule> MAIN_BOARD_RULES = List.of(
      LimitRule.of("SH-MAIN-NORMAL", "SH", "MAIN", "NORMAL",
          new BigDecimal("0.10"), new BigDecimal("0.10"), 100));

  @Test
  void classifiesLimitUpIntoRiseCount() {
    var breadth = calculate(List.of(quote("a", "10.00", "11.00")));

    assertThat(breadth.riseCount()).isEqualTo(1);
    assertThat(breadth.limitUpCount()).isEqualTo(1);
    assertThat(breadth.fallCount()).isZero();
    assertThat(breadth.limitDownCount()).isZero();
  }

  @Test
  void classifiesOrdinaryRiseWithoutLimitUp() {
    var breadth = calculate(List.of(quote("a", "10.00", "10.50")));

    assertThat(breadth.riseCount()).isEqualTo(1);
    assertThat(breadth.limitUpCount()).isZero();
  }

  @Test
  void classifiesFlatAndFallAndLimitDown() {
    var breadth = calculate(List.of(
        quote("flat", "10.00", "10.00"),
        quote("fall", "10.00", "9.50"),
        quote("limitDown", "10.00", "9.00")));

    assertThat(breadth.flatCount()).isEqualTo(1);
    assertThat(breadth.fallCount()).isEqualTo(2);
    assertThat(breadth.limitDownCount()).isEqualTo(1);
    assertThat(breadth.limitUpCount()).isZero();
  }

  @Test
  void suspendedSecurityIsCountedSeparatelyAndNotAsRiseFallOrFlat() {
    var breadth = calculate(List.of(
        suspended("halt", "10.00", "11.00"),
        quote("normal", "10.00", "10.00")));

    assertThat(breadth.suspendedCount()).isEqualTo(1);
    assertThat(breadth.riseCount()).isZero();
    assertThat(breadth.fallCount()).isZero();
    assertThat(breadth.flatCount()).isEqualTo(1);
  }

  @Test
  void limitUpIsASubsetOfRiseAndLimitDownIsASubsetOfFall() {
    var breadth = calculate(List.of(
        quote("limitUp", "10.00", "11.00"),
        quote("up", "10.00", "10.50"),
        quote("limitDown", "10.00", "9.00"),
        quote("down", "10.00", "9.50"),
        quote("flat", "10.00", "10.00"),
        suspended("halt", "10.00", "10.00")));

    assertThat(breadth.limitUpCount()).isLessThanOrEqualTo(breadth.riseCount());
    assertThat(breadth.limitDownCount()).isLessThanOrEqualTo(breadth.fallCount());
    assertThat(breadth.riseCount()).isEqualTo(2);
    assertThat(breadth.fallCount()).isEqualTo(2);
  }

  @Test
  void totalCountIsTheSumOfTheFourExhaustiveStates() {
    var breadth = calculate(List.of(
        quote("limitUp", "10.00", "11.00"),
        quote("down", "10.00", "9.50"),
        quote("flat", "10.00", "10.00"),
        suspended("halt", "10.00", "10.00")));

    assertThat(breadth.totalCount())
        .isEqualTo(breadth.riseCount() + breadth.fallCount()
            + breadth.flatCount() + breadth.suspendedCount())
        .isEqualTo(4);
  }

  @Test
  void judgesLimitUpByPriceNotByRate() {
    // 10.03 × 1.10 = 11.033 → 限价 11.03，涨幅 9.97%：按比例判定会漏掉这个涨停
    var breadth = calculate(List.of(quote("a", "10.03", "11.03")));

    assertThat(breadth.limitUpCount()).isEqualTo(1);
  }

  @Test
  void priceJustBelowLimitIsNotCountedAsLimitUp() {
    var breadth = calculate(List.of(quote("a", "10.03", "11.02")));

    assertThat(breadth.limitUpCount()).isZero();
    assertThat(breadth.riseCount()).isEqualTo(1);
  }

  @Test
  void missingRuleKeepsRiseClassificationButNeverCountsAsLimitUp() {
    var quotes = List.of(quote("a", "10.00", "11.00"));
    var breadth = BreadthCalculator.calculate(quotes, List.of(), RULE_DATE);

    assertThat(breadth.riseCount()).isEqualTo(1);
    assertThat(breadth.limitUpCount()).isZero();
  }

  @Test
  void noPriceLimitRuleNeverCountsAsLimitUp() {
    var rules = List.of(new LimitRule(
        "SH-MAIN-NEW", "SH", "MAIN", "STOCK", "NORMAL", null, null,
        null, null, true, LocalDate.of(2000, 1, 1), null, 10));
    var breadth = BreadthCalculator.calculate(
        List.of(quote("a", "10.00", "15.00")), rules, RULE_DATE);

    assertThat(breadth.riseCount()).isEqualTo(1);
    assertThat(breadth.limitUpCount()).isZero();
  }

  @Test
  void emptyUniverseProducesAllZeroCounts() {
    var breadth = BreadthCalculator.calculate(List.of(), MAIN_BOARD_RULES, RULE_DATE);

    assertThat(breadth.totalCount()).isZero();
    assertThat(breadth.limitUpCount()).isZero();
  }

  private static BreadthData calculate(List<SecurityQuote> quotes) {
    return BreadthCalculator.calculate(quotes, MAIN_BOARD_RULES, RULE_DATE);
  }

  private static SecurityQuote quote(String code, String previousClose, String latest) {
    return new SecurityQuote(
        "id-" + code, code, "SH", "测试证券", "MAIN", "STOCK", false, false,
        LocalDate.of(2010, 1, 4), new BigDecimal(previousClose), new BigDecimal(latest));
  }

  private static SecurityQuote suspended(String code, String previousClose, String latest) {
    return new SecurityQuote(
        "id-" + code, code, "SH", "停牌证券", "MAIN", "STOCK", false, true,
        LocalDate.of(2010, 1, 4), new BigDecimal(previousClose), new BigDecimal(latest));
  }
}
