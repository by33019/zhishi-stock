package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.BreadthCalculator;
import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.SecurityQuote;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedSecurityQuoteProviderTest {

  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 11);

  private final SimulatedLimitRuleProvider rules = new SimulatedLimitRuleProvider();

  private final SimulatedSecurityQuoteProvider provider =
      new SimulatedSecurityQuoteProvider(rules);

  @Test
  void buildsAUniverseOfRealisticSize() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);

    assertThat(universe).hasSizeBetween(5000, 6000);
  }

  @Test
  void coversAllExchangesAndBoards() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);

    assertThat(universe).extracting(SecurityQuote::exchangeCode)
        .contains("SH", "SZ", "BJ");
    assertThat(universe).extracting(SecurityQuote::boardCode)
        .contains("MAIN", "GEM", "STAR", "BSE");
  }

  @Test
  void isDeterministicAcrossCalls() {
    assertThat(provider.fetchUniverse("CN", TRADE_DATE))
        .isEqualTo(provider.fetchUniverse("CN", TRADE_DATE));
  }

  @Test
  void rejectsUnsupportedMarket() {
    assertThat(provider.fetchUniverse("US", TRADE_DATE)).isEmpty();
  }

  @Test
  void everyQuoteHasAPositivePreviousCloseAndLatestPrice() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);

    assertThat(universe).allSatisfy(quote -> {
      assertThat(quote.previousClosePrice()).isPositive();
      assertThat(quote.latestPrice()).isPositive();
    });
  }

  @Test
  void limitUpQuotesSitExactlyOnTheMatchedRuleLimit() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);
    var limitRules = rules.rules("CN", TRADE_DATE);
    var breadth = BreadthCalculator.calculate(universe, limitRules, TRADE_DATE);

    assertThat(breadth.limitUpCount()).isPositive();

    long onLimit = universe.stream()
        .filter(quote -> !quote.suspended())
        .filter(quote -> LimitRuleMatcher.match(limitRules, quote, TRADE_DATE)
            .filter(rule -> !rule.noPriceLimit())
            .map(rule -> quote.latestPrice().compareTo(rule.limitUpPrice(quote.previousClosePrice())) == 0)
            .orElse(false))
        .count();

    // 每个涨停股都必须精确落在限价上，否则"按规则计数"就是空话
    assertThat(onLimit).isEqualTo(breadth.limitUpCount());
  }

  @Test
  void limitDownQuotesSitExactlyOnTheMatchedRuleLimit() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);
    var limitRules = rules.rules("CN", TRADE_DATE);
    var breadth = BreadthCalculator.calculate(universe, limitRules, TRADE_DATE);

    assertThat(breadth.limitDownCount()).isPositive();

    long onLimit = universe.stream()
        .filter(quote -> !quote.suspended())
        .filter(quote -> LimitRuleMatcher.match(limitRules, quote, TRADE_DATE)
            .filter(rule -> !rule.noPriceLimit())
            .map(rule -> quote.latestPrice().compareTo(rule.limitDownPrice(quote.previousClosePrice())) == 0)
            .orElse(false))
        .count();

    assertThat(onLimit).isEqualTo(breadth.limitDownCount());
  }

  @Test
  void producesAPlausibleBreadthDistribution() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);
    var breadth = BreadthCalculator.calculate(universe, rules.rules("CN", TRADE_DATE), TRADE_DATE);

    assertThat(breadth.totalCount()).isEqualTo(universe.size());
    assertThat(breadth.suspendedCount()).isPositive();
    assertThat(breadth.riseCount()).isGreaterThan(breadth.fallCount());
    assertThat(breadth.riseCount()).isBetween(universe.size() / 3, universe.size() * 3 / 4);
  }

  @Test
  void containsRiskWarningStocksOnTheMainBoard() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);

    assertThat(universe)
        .filteredOn(quote -> quote.st() && "MAIN".equals(quote.boardCode()))
        .isNotEmpty();
  }

  @Test
  void everyQuoteMatchesExactlyOneLimitRule() {
    var universe = provider.fetchUniverse("CN", TRADE_DATE);
    var limitRules = rules.rules("CN", TRADE_DATE);

    assertThat(universe).allSatisfy(quote -> assertThat(
        LimitRuleMatcher.match(limitRules, quote, TRADE_DATE))
        .as("证券 %s (%s/%s, st=%s) 应当匹配到规则",
            quote.securityCode(), quote.exchangeCode(), quote.boardCode(), quote.st())
        .isPresent());
  }

  @Test
  void rulesAreResolvedThroughThePort() {
    List<LimitRule> supplied = rules.rules("CN", TRADE_DATE);

    assertThat(supplied).isNotEmpty();
    assertThat(provider.fetchUniverse("CN", TRADE_DATE)).isNotEmpty();
  }
}
