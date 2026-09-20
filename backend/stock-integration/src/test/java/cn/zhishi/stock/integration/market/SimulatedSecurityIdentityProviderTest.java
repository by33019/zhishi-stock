package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 证券身份解析：对外 {@code securityId} ↔ 持久化代理键。
 *
 * <p>用**真实的**模拟市场栈（限幅规则 → 个股行情 → 主数据 → 身份解析），
 * 而不是手搓几条 {@code SecuritySummary}：这层抽象的全部价值就是"代理键是全集的往返映射"，
 * 用假数据测它等于把最容易错的部分（正反函数是否互逆、代码是否恰好 6 位）测掉了。
 */
class SimulatedSecurityIdentityProviderTest {

  /** {@code SimulatedSecurityQuoteProvider} 的代码段合计 5149 只，主数据 1:1 投影。 */
  private static final int UNIVERSE_SIZE = 5149;

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-21T01:30:00Z"), ZoneId.of("Asia/Shanghai"));

  private final SecurityMasterProvider master = masterProvider();
  private final SecurityIdentityProvider identities = new SimulatedSecurityIdentityProvider(master);

  @Test
  void roundTripsEverySecurityOfTheWholeUniverseThroughItsStorageId() {
    List<SecuritySummary> universe = master.findAll("CN");
    assertThat(universe).hasSize(UNIVERSE_SIZE);

    Map<String, SecurityIdentity> resolved = identities.resolveAll(
        universe.stream().map(SecuritySummary::securityId).toList());
    assertThat(resolved).hasSize(UNIVERSE_SIZE);

    Map<Long, SecurityIdentity> byStorage = identities.findByStorageIds(
        resolved.values().stream().map(SecurityIdentity::storageId).toList());
    assertThat(byStorage).hasSize(UNIVERSE_SIZE);

    for (SecuritySummary summary : universe) {
      SecurityIdentity identity = resolved.get(summary.securityId());
      assertThat(identity).describedAs("%s 应当可解析", summary.securityId()).isNotNull();
      assertThat(identity.summary()).isEqualTo(summary);
      assertThat(byStorage.get(identity.storageId())).isEqualTo(identity);
      assertThat(SimulatedSecurityIds.securityIdOfStorageId(identity.storageId()))
          .describedAs("代理键必须能反解回原 securityId")
          .isEqualTo(summary.securityId());
    }
    assertThat(resolved.values().stream().map(SecurityIdentity::storageId).distinct())
        .describedAs("代理键必须两两不同，否则两个证券会指向同一条自选")
        .hasSize(UNIVERSE_SIZE);
  }

  @Test
  void resolveIsTheSingleKeyVersionOfResolveAll() {
    SecurityIdentity bulk = identities.resolveAll(List.of("sim-600519")).get("sim-600519");

    assertThat(identities.resolve("sim-600519")).contains(bulk);
    assertThat(bulk.storageId()).isEqualTo(600_519L);
    assertThat(bulk.securityId()).isEqualTo("sim-600519");
    assertThat(bulk.summary().securityCode()).isEqualTo("600519");
  }

  @Test
  void rejectsIdsThatAreNotInTheSimulatedFormat() {
    for (String candidate : List.of(
        "600519", "sim-60051", "sim-6005190", "sim-ABCDEF", "sim-", "", "sh.600519", "sim-600519 ")) {
      assertThat(identities.resolve(candidate))
          .describedAs("%s 不是本模拟源的 securityId 格式", candidate)
          .isEmpty();
    }
    assertThat(identities.resolve(null)).isEmpty();
  }

  @Test
  void ignoresUnknownKeysInsteadOfMappingThemToSomething() {
    assertThat(identities.resolveAll(List.of("sim-999999"))).isEmpty();
    assertThat(identities.findByStorageIds(List.of(999_999L))).isEmpty();
    assertThat(identities.resolveAll(List.of())).isEmpty();
    assertThat(identities.findByStorageIds(List.of())).isEmpty();
  }

  @Test
  void resolveAllKeepsOnlyTheKeysItCouldResolve() {
    Map<String, SecurityIdentity> resolved =
        identities.resolveAll(List.of("sim-600519", "sim-999999", "600519"));

    assertThat(resolved).containsOnlyKeys("sim-600519");
  }

  private static SecurityMasterProvider masterProvider() {
    TradingCalendarProvider calendar = new SimulatedTradingCalendarProvider(CLOCK, Set.of());
    LimitRuleProvider rules = new SimulatedLimitRuleProvider();
    SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(rules);
    return new SimulatedSecurityMasterProvider(quotes, calendar, CLOCK);
  }
}
