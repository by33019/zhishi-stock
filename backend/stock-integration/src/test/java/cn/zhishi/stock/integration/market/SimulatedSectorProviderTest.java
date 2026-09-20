package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorType;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 模拟板块源：板块构成、成分关系基数与确定性。
 *
 * <p>关系基数是最值得断言的部分——"每只证券恰好有一个主行业"这类不变量一旦被破坏，
 * 板块排行与成分股列表会给出互相矛盾的结果，而不会有任何一处抛异常。
 */
class SimulatedSectorProviderTest {

  private static final int UNIVERSE_SIZE = 400;

  @Test
  void exposesThirtyNineSectorsWithExpectedTypeDistribution() {
    List<Sector> sectors = provider().findAll("CN");

    assertThat(sectors).hasSize(39);
    assertThat(sectors.stream().filter(sector -> sector.isType(SectorType.INDUSTRY)).count())
        .isEqualTo(25);
    assertThat(sectors.stream().filter(sector -> sector.isType(SectorType.CONCEPT)).count())
        .isEqualTo(8);
    assertThat(sectors.stream().filter(sector -> sector.isType(SectorType.REGION)).count())
        .isEqualTo(6);
    assertThat(sectors).allSatisfy(sector -> assertThat(sector.status()).isEqualTo("ACTIVE"));
  }

  @Test
  void assignsStableIdsAndCodesToEverySector() {
    List<Sector> sectors = provider().findAll("CN");

    assertThat(sectors).allSatisfy(sector -> {
      assertThat(sector.sectorId()).matches("sim-bk\\d{4}");
      assertThat(sector.sectorCode()).matches("BK\\d{4}");
      assertThat(sector.sectorId().substring("sim-bk".length()))
          .isEqualTo(sector.sectorCode().substring("BK".length()));
    });
    assertThat(sectors.stream().map(Sector::sectorId).distinct()).hasSize(39);
    assertThat(sectors.stream().map(Sector::sectorCode).distinct()).hasSize(39);
  }

  /** 二级行业的父级必须是一个存在的一级行业板块，否则 SEC-03 的 `parent` 会指向虚空。 */
  @Test
  void nestsEveryIndustryUnderAnExistingParent() {
    List<Sector> sectors = provider().findAll("CN");
    Map<String, Sector> byId = sectors.stream()
        .collect(Collectors.toMap(Sector::sectorId, sector -> sector));

    List<Sector> industries = sectors.stream()
        .filter(sector -> sector.isType(SectorType.INDUSTRY) && sector.levelNo() == 2)
        .toList();
    assertThat(industries).hasSize(20);

    assertThat(industries).allSatisfy(industry -> {
      assertThat(industry.parentId()).isNotNull();
      Sector parent = byId.get(industry.parentId());
      assertThat(parent).isNotNull();
      assertThat(parent.levelNo()).isEqualTo(1);
      assertThat(parent.isType(SectorType.INDUSTRY)).isTrue();
    });
  }

  /** 每个板块恰好一个主行业：多于一个会让"所属行业"没有唯一答案，少于一个会让证券消失。 */
  @Test
  void givesEverySecurityExactlyOnePrimaryIndustry() {
    Map<String, List<SectorMember>> memberships = provider().memberships("CN", null);
    Map<String, Sector> byId = sectorsById();

    List<SectorMember> primaries = memberships.values().stream()
        .flatMap(List::stream)
        .filter(member -> SectorMember.RELATION_PRIMARY.equals(member.relationType()))
        .toList();

    assertThat(primaries).hasSize(UNIVERSE_SIZE);
    assertThat(primaries).allSatisfy(member -> {
      assertThat(member.isPrimary()).isTrue();
      assertThat(byId.get(member.sectorId()).levelNo()).isEqualTo(2);
    });
  }

  /**
   * 每只证券恰好 1 个二级行业、1 个一级大类、1 个地域、0~1 个概念。
   *
   * <p>大类关系必须是其主行业所属大类——两处各写一遍归属的话，
   * 证券会出现在"不属于它的行业"的大类里。
   *
   * <p>筛"大类"必须同时限定 `INDUSTRY` **和** `levelNo == 1`：地域板块的 `levelNo`
   * 也是 1（它本就没有父级），只看层级会把地域算成大类，于是每只证券都有 2 个"大类"。
   */
  @Test
  void derivesGroupMembershipFromPrimaryIndustry() {
    Map<String, Sector> byId = sectorsById();

    for (List<SectorMember> members : membersBySecurity(provider()).values()) {
      List<SectorMember> industries = members.stream()
          .filter(member -> byId.get(member.sectorId()).isType(SectorType.INDUSTRY)
              && byId.get(member.sectorId()).levelNo() == 2)
          .toList();
      List<SectorMember> groups = members.stream()
          .filter(member -> byId.get(member.sectorId()).isType(SectorType.INDUSTRY)
              && byId.get(member.sectorId()).levelNo() == 1)
          .toList();

      assertThat(industries).hasSize(1);
      assertThat(groups).hasSize(1);
      assertThat(groups.get(0).sectorId()).isEqualTo(byId.get(industries.get(0).sectorId()).parentId());
    }
  }

  @Test
  void givesEverySecurityExactlyOneRegionAndAtMostOneConcept() {
    Map<String, Sector> byId = sectorsById();
    Map<String, List<SectorMember>> bySecurity = membersBySecurity(provider());

    assertThat(bySecurity).hasSize(UNIVERSE_SIZE);
    for (List<SectorMember> members : bySecurity.values()) {
      assertThat(members.stream()
          .filter(member -> byId.get(member.sectorId()).isType(SectorType.REGION))
          .count()).isEqualTo(1);
      assertThat(members.stream()
          .filter(member -> byId.get(member.sectorId()).isType(SectorType.CONCEPT))
          .count()).isLessThanOrEqualTo(1);
    }
  }

  @Test
  void coversEachIndustryAndRegionCompletely() {
    Map<String, List<SectorMember>> memberships = provider().memberships("CN", null);
    Map<String, Sector> byId = sectorsById();

    assertThat(totalMembersOfType(memberships, byId, SectorType.INDUSTRY, 2)).isEqualTo(UNIVERSE_SIZE);
    assertThat(totalMembersOfType(memberships, byId, SectorType.REGION, 1)).isEqualTo(UNIVERSE_SIZE);
    assertThat(totalMembersOfType(memberships, byId, SectorType.INDUSTRY, 1)).isEqualTo(UNIVERSE_SIZE);
  }

  /** 概念覆盖率应当落在设计值附近；完全覆盖或完全不覆盖都说明哈希塌了。 */
  @Test
  void keepsConceptCoverageNearDesignValue() {
    Map<String, List<SectorMember>> memberships = provider().memberships("CN", null);
    Map<String, Sector> byId = sectorsById();

    long covered = memberships.values().stream()
        .flatMap(List::stream)
        .filter(member -> byId.get(member.sectorId()).isType(SectorType.CONCEPT))
        .count();

    assertThat(covered).isBetween(UNIVERSE_SIZE * 15L / 100, UNIVERSE_SIZE * 35L / 100);
  }

  /**
   * 归属只由证券代码决定，与证券在全集里的顺序无关。
   *
   * <p>若改用"在全集中的序号"，生成顺序一变同一只证券就会换板块——
   * 这种漂移不会有任何测试报错，只会让历史对比失去意义。
   *
   * <p>比较的是**每只证券的板块集合**，不是每个板块的成员列表顺序：
   * 后者本来就是"主数据给出顺序"的投影，反转全集顺序时顺序理应随之反转。
   */
  @Test
  void derivesMembershipFromCodeRatherThanListOrder() {
    assertThat(sectorIdsBySecurity(providerOf(reversedUniverse())))
        .isEqualTo(sectorIdsBySecurity(provider()));
  }

  /** 生效窗口之外的日期不应有成分——"某天的成分股"必须能按日期还原。 */
  @Test
  void returnsNoMembershipBeforeEffectiveDate() {
    SimulatedSectorProvider provider = provider();

    assertThat(provider.memberships("CN", LocalDate.of(2009, 12, 31))).isEmpty();
    assertThat(provider.memberships("CN", LocalDate.of(2010, 1, 4))).isNotEmpty();
  }

  @Test
  void isDeterministicAcrossCalls() {
    SimulatedSectorProvider provider = provider();

    assertThat(provider.findAll("CN")).isEqualTo(provider.findAll("CN"));
    assertThat(provider.memberships("CN", null)).isEqualTo(provider.memberships("CN", null));
  }

  @Test
  void returnsNothingForUnsupportedMarket() {
    SimulatedSectorProvider provider = provider();

    assertThat(provider.findAll("US")).isEmpty();
    assertThat(provider.memberships("US", null)).isEmpty();
  }

  // ---------- 桩数据 ----------

  private static SimulatedSectorProvider provider() {
    return providerOf(universe());
  }

  private static SimulatedSectorProvider providerOf(List<SecuritySummary> universe) {
    SecurityMasterProvider master = marketCode -> "CN".equals(marketCode) ? universe : List.of();
    return new SimulatedSectorProvider(master);
  }

  private static Map<String, Sector> sectorsById() {
    return provider().findAll("CN").stream()
        .collect(Collectors.toMap(Sector::sectorId, sector -> sector));
  }

  /** 把"按板块分组"的关系转置成"按证券分组"——成分基数的断言都以证券为观察单位。 */
  private static Map<String, List<SectorMember>> membersBySecurity(SimulatedSectorProvider provider) {
    Map<String, List<SectorMember>> grouped = new LinkedHashMap<>();
    provider.memberships("CN", null).values().stream()
        .flatMap(List::stream)
        .forEach(member -> grouped
            .computeIfAbsent(member.securityId(), key -> new ArrayList<>())
            .add(member));
    return grouped;
  }

  /** 每只证券所属的板块 ID 集合（已排序，便于与列表顺序解耦地比较）。 */
  private static Map<String, List<String>> sectorIdsBySecurity(SimulatedSectorProvider provider) {
    Map<String, List<String>> grouped = new LinkedHashMap<>();
    membersBySecurity(provider).forEach((securityId, members) -> grouped.put(
        securityId, members.stream().map(SectorMember::sectorId).sorted().toList()));
    return grouped;
  }

  private static long totalMembersOfType(
      Map<String, List<SectorMember>> memberships,
      Map<String, Sector> byId,
      SectorType type,
      int levelNo) {
    return memberships.values().stream()
        .flatMap(List::stream)
        .filter(member -> byId.get(member.sectorId()).isType(type))
        .filter(member -> byId.get(member.sectorId()).levelNo() == levelNo)
        .count();
  }

  private static List<SecuritySummary> universe() {
    List<SecuritySummary> summaries = new ArrayList<>();
    for (int index = 0; index < UNIVERSE_SIZE; index++) {
      summaries.add(summary("600" + padded(index)));
    }
    return List.copyOf(summaries);
  }

  private static List<SecuritySummary> reversedUniverse() {
    List<SecuritySummary> summaries = new ArrayList<>(universe());
    java.util.Collections.reverse(summaries);
    return List.copyOf(summaries);
  }

  private static SecuritySummary summary(String code) {
    return new SecuritySummary(
        "sim-" + code, "SH." + code, code, "模拟证券" + code,
        "SH", "STOCK", "MAIN", "LISTED", false, false, 2, null, null);
  }

  private static String padded(int value) {
    String digits = Integer.toString(value);
    return "0".repeat(3 - digits.length()) + digits;
  }
}
