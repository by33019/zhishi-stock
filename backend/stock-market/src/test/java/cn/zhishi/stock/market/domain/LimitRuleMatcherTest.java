package cn.zhishi.stock.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LimitRuleMatcherTest {

  private static final LocalDate RULE_DATE = LocalDate.of(2026, 9, 11);

  @Test
  void matchesOnExactStaticAttributes() {
    var rules = List.of(normalRule("SH-MAIN-NORMAL", "SH", "MAIN", 100));

    assertThat(LimitRuleMatcher.match(rules, quote("SH", "MAIN", false, null), RULE_DATE))
        .map(LimitRule::ruleCode)
        .contains("SH-MAIN-NORMAL");
  }

  @Test
  void doesNotMatchWhenExchangeOrBoardDiffers() {
    var rules = List.of(normalRule("SZ-GEM-NORMAL", "SZ", "GEM", 100));

    assertThat(LimitRuleMatcher.match(rules, quote("SZ", "MAIN", false, null), RULE_DATE))
        .isEmpty();
    assertThat(LimitRuleMatcher.match(rules, quote("SH", "GEM", false, null), RULE_DATE))
        .isEmpty();
  }

  @Test
  void doesNotMatchNormalRuleForStSecurity() {
    var rules = List.of(normalRule("SH-MAIN-NORMAL", "SH", "MAIN", 100));

    assertThat(LimitRuleMatcher.match(rules, quote("SH", "MAIN", true, null), RULE_DATE))
        .isEmpty();
  }

  @Test
  void honoursEffectiveWindow() {
    var rules = List.of(new LimitRule(
        "SH-MAIN-FUTURE", "SH", "MAIN", "STOCK", "NORMAL", null, null,
        new BigDecimal("0.10"), new BigDecimal("0.10"), false,
        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), 100));

    assertThat(LimitRuleMatcher.match(rules, quote("SH", "MAIN", false, null), RULE_DATE))
        .isEmpty();
    assertThat(LimitRuleMatcher.match(
        rules, quote("SH", "MAIN", false, null), LocalDate.of(2026, 10, 1)))
        .isPresent();
    assertThat(LimitRuleMatcher.match(
        rules, quote("SH", "MAIN", false, null), LocalDate.of(2027, 1, 1)))
        .isEmpty();
  }

  @Test
  void honoursListingDayWindow() {
    var newListingOnly = new LimitRule(
        "SH-MAIN-NEW", "SH", "MAIN", "STOCK", "NORMAL", 0, 0,
        null, null, true, LocalDate.of(2000, 1, 1), null, 10);
    var rules = List.of(normalRule("SH-MAIN-NORMAL", "SH", "MAIN", 100), newListingOnly);

    var listedToday = quote("SH", "MAIN", false, RULE_DATE);
    var listedLongAgo = quote("SH", "MAIN", false, LocalDate.of(2010, 1, 4));

    assertThat(LimitRuleMatcher.match(rules, listedToday, RULE_DATE))
        .map(LimitRule::ruleCode)
        .contains("SH-MAIN-NEW");
    assertThat(LimitRuleMatcher.match(rules, listedLongAgo, RULE_DATE))
        .map(LimitRule::ruleCode)
        .contains("SH-MAIN-NORMAL");
  }

  @Test
  void picksLowestPriorityNumber() {
    var rules = List.of(
        normalRule("SH-MAIN-LOW", "SH", "MAIN", 500),
        normalRule("SH-MAIN-HIGH", "SH", "MAIN", 100));

    assertThat(LimitRuleMatcher.match(rules, quote("SH", "MAIN", false, null), RULE_DATE))
        .map(LimitRule::ruleCode)
        .contains("SH-MAIN-HIGH");
  }

  @Test
  void breaksPriorityTiesByRuleCodeSoResultIsDeterministic() {
    var rules = List.of(
        normalRule("SH-MAIN-BBB", "SH", "MAIN", 100),
        normalRule("SH-MAIN-AAA", "SH", "MAIN", 100));

    assertThat(LimitRuleMatcher.match(rules, quote("SH", "MAIN", false, null), RULE_DATE))
        .map(LimitRule::ruleCode)
        .contains("SH-MAIN-AAA");
  }

  @Test
  void returnsEmptyWhenNoRuleApplies() {
    assertThat(LimitRuleMatcher.match(List.of(), quote("SH", "MAIN", false, null), RULE_DATE))
        .isEmpty();
  }

  @Test
  void computesLimitPricesByRoundingToCent() {
    var rule = LimitRule.of("SH-MAIN-NORMAL", "SH", "MAIN", "NORMAL",
        new BigDecimal("0.10"), new BigDecimal("0.10"), 100);

    // 10.03 × 1.10 = 11.033 → 11.03，涨幅 9.97%：必须按"价格"而非"比例"判定涨停
    assertThat(rule.limitUpPrice(new BigDecimal("10.03"))).isEqualByComparingTo("11.03");
    assertThat(rule.limitDownPrice(new BigDecimal("10.03"))).isEqualByComparingTo("9.03");
  }

  private static LimitRule normalRule(
      String ruleCode, String exchange, String board, int priorityNo) {
    return LimitRule.of(ruleCode, exchange, board, "NORMAL",
        new BigDecimal("0.10"), new BigDecimal("0.10"), priorityNo);
  }

  private static SecurityQuote quote(
      String exchange, String board, boolean st, LocalDate listedDate) {
    return new SecurityQuote(
        "id-" + exchange + "-" + board,
        "600000",
        exchange,
        "测试证券",
        board,
        "STOCK",
        st,
        false,
        listedDate == null ? LocalDate.of(2010, 1, 4) : listedDate,
        new BigDecimal("10.00"),
        new BigDecimal("10.00"));
  }
}
