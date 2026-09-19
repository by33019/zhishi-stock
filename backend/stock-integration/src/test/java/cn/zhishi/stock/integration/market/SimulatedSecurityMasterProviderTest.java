package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulatedSecurityMasterProviderTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final SimulatedSecurityMasterProvider PROVIDER = new SimulatedSecurityMasterProvider(
      new SimulatedSecurityQuoteProvider(new SimulatedLimitRuleProvider()),
      new SimulatedTradingCalendarProvider(CLOCK, Set.of()),
      CLOCK);

  @Test
  void returnsTheFullDeterministicUniverse() {
    assertThat(PROVIDER.findAll("CN")).hasSize(5149);
  }

  @Test
  void keepsSixDigitCodesAndUniqueExchangeCodePairs() {
    List<SecuritySummary> universe = PROVIDER.findAll("CN");

    assertThat(universe).allSatisfy(security ->
        assertThat(security.securityCode()).hasSize(6).containsOnlyDigits());
    assertThat(universe)
        .extracting(security -> security.exchangeCode() + "." + security.securityCode())
        .doesNotHaveDuplicates();
  }

  @Test
  void buildsWellFormedAndUniqueFullSymbols() {
    List<SecuritySummary> universe = PROVIDER.findAll("CN");

    assertThat(universe).allSatisfy(security -> assertThat(security.fullSymbol())
        .isEqualTo(security.exchangeCode() + "." + security.securityCode()));
    assertThat(universe).extracting(SecuritySummary::fullSymbol).doesNotHaveDuplicates();
    assertThat(universe).extracting(SecuritySummary::securityId).doesNotHaveDuplicates();
  }

  @Test
  void keepsListingStatusConsistentWithSuspensionFlag() {
    List<SecuritySummary> universe = PROVIDER.findAll("CN");

    assertThat(universe).allSatisfy(security -> {
      assertThat(security.listingStatus())
          .isEqualTo(security.isSuspended() ? "SUSPENDED" : "LISTED");
      assertThat(security.priceScale()).isEqualTo(2);
      assertThat(security.securityType()).isEqualTo("STOCK");
    });
    long suspended = universe.stream().filter(SecuritySummary::isSuspended).count();
    assertThat(suspended).isPositive().isLessThan(universe.size() / 10);
  }

  @Test
  void leavesPinyinEmptyBecauseSyntheticNamesHaveNoVerifiablePinyin() {
    assertThat(PROVIDER.findAll("CN")).allSatisfy(security -> {
      assertThat(security.pinyin()).isNull();
      assertThat(security.pinyinAbbr()).isNull();
    });
  }

  @Test
  void isDeterministicAcrossCallsAndInstances() {
    var other = new SimulatedSecurityMasterProvider(
        new SimulatedSecurityQuoteProvider(new SimulatedLimitRuleProvider()),
        new SimulatedTradingCalendarProvider(CLOCK, Set.of()),
        CLOCK);

    assertThat(PROVIDER.findAll("CN")).isEqualTo(PROVIDER.findAll("CN"));
    assertThat(other.findAll("CN")).isEqualTo(PROVIDER.findAll("CN"));
  }

  @Test
  void returnsEmptyForUnsupportedMarket() {
    assertThat(PROVIDER.findAll("US")).isEmpty();
    assertThat(PROVIDER.findAll(null)).isEmpty();
  }

  /**
   * 验收标准「搜索 P95 < 500ms」的落实。
   *
   * <p>阈值刻意取得极宽松（实测单次在毫秒级，留出两个数量级余量），
   * 目的是让这条测试在 CI 上**不会抖动**，同时一旦搜索退化成 O(n²) 或引入阻塞 IO 会立刻变红。
   */
  @Test
  void keepsSearchLatencyWithinBudgetOverTheFullUniverse() {
    var service = new SecurityQueryService(PROVIDER);
    for (int i = 0; i < 20; i++) {
      service.search("6000", null, null, 20);
    }

    long[] samples = new long[100];
    for (int i = 0; i < samples.length; i++) {
      long start = System.nanoTime();
      service.search("6000", null, null, 20);
      samples[i] = System.nanoTime() - start;
    }
    Arrays.sort(samples);

    long p95Nanos = samples[(int) Math.ceil(samples.length * 0.95) - 1];
    assertThat(Duration.ofNanos(p95Nanos)).isLessThan(Duration.ofMillis(500));
  }
}
