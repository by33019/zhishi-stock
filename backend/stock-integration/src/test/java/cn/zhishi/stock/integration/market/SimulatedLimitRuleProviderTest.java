package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.SecurityQuote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedLimitRuleProviderTest {

  private static final LocalDate RULE_DATE = LocalDate.of(2026, 9, 11);

  private final SimulatedLimitRuleProvider provider = new SimulatedLimitRuleProvider();

  @Test
  void declaresRulesForEveryExchangeBoardAndRiskStatusCombination() {
    var rules = provider.rules("CN", RULE_DATE);

    assertThat(rules).hasSize(10);
    assertThat(rules).allSatisfy(rule -> {
      assertThat(rule.effectiveFrom()).isBeforeOrEqualTo(RULE_DATE);
      assertThat(rule.effectiveTo()).isNull();
      assertThat(rule.noPriceLimit()).isFalse();
    });
  }

  @Test
  void appliesTenPercentOnMainBoard() {
    assertThat(matchedRate("SH", "MAIN", false)).isEqualByComparingTo("0.10");
    assertThat(matchedRate("SZ", "MAIN", false)).isEqualByComparingTo("0.10");
  }

  @Test
  void appliesFivePercentOnMainBoardRiskWarningStocks() {
    assertThat(matchedRate("SH", "MAIN", true)).isEqualByComparingTo("0.05");
    assertThat(matchedRate("SZ", "MAIN", true)).isEqualByComparingTo("0.05");
  }

  @Test
  void appliesTwentyPercentOnGrowthAndStarBoardsRegardlessOfRiskStatus() {
    assertThat(matchedRate("SZ", "GEM", false)).isEqualByComparingTo("0.20");
    assertThat(matchedRate("SZ", "GEM", true)).isEqualByComparingTo("0.20");
    assertThat(matchedRate("SH", "STAR", false)).isEqualByComparingTo("0.20");
    assertThat(matchedRate("SH", "STAR", true)).isEqualByComparingTo("0.20");
  }

  @Test
  void appliesThirtyPercentOnBeijingExchange() {
    assertThat(matchedRate("BJ", "BSE", false)).isEqualByComparingTo("0.30");
    assertThat(matchedRate("BJ", "BSE", true)).isEqualByComparingTo("0.30");
  }

  @Test
  void isDeterministicAcrossCalls() {
    assertThat(provider.rules("CN", RULE_DATE)).isEqualTo(provider.rules("CN", RULE_DATE));
  }

  private BigDecimal matchedRate(String exchange, String board, boolean st) {
    var rules = provider.rules("CN", RULE_DATE);
    var quote = new SecurityQuote(
        "id", "600000", exchange, "测试证券", board, "STOCK", st, false,
        LocalDate.of(2010, 1, 4), new BigDecimal("10.00"), new BigDecimal("10.00"));
    return LimitRuleMatcher.match(rules, quote, RULE_DATE)
        .map(LimitRule::upperLimitRate)
        .orElseThrow(() -> new AssertionError("未匹配到限幅规则：" + exchange + "/" + board + "/st=" + st));
  }

  @Test
  void upperAndLowerRatesAreSymmetric() {
    List<LimitRule> rules = provider.rules("CN", RULE_DATE);
    assertThat(rules).allSatisfy(rule ->
        assertThat(rule.lowerLimitRate()).isEqualByComparingTo(rule.upperLimitRate()));
  }
}
