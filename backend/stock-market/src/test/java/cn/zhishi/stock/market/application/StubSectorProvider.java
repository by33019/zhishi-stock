package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SectorType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桩板块源：只提供用例层测试真正关心的那三个板块。
 *
 * <p>刻意**不复用** {@code SimulatedSectorProvider}：那会把 39 个板块、5149 只证券
 * 以及哈希分桶算法一起带进用例层测试，断言就只能写成"某板块有多少只"这类随模拟算法
 * 漂移的数字，而不是"筛选是否真的按成分关系生效"。真实模拟源的行为由
 * {@code SimulatedSectorProviderTest} 单独覆盖。
 *
 * <p>三个板块的分工：
 * <ul>
 *   <li>{@link #GROUP_ID} 与 {@link #INDUSTRY_ID} 成分**有重叠但不相同**——
 *       重叠用来证明"两个板块各自独立取数"，否则一个板块的筛选实现写错成另一个
 *       也不会有测试变红；</li>
 *   <li>{@link #EMPTY_ID} 存在但没有成分关系。它与"板块 ID 不存在"是两种语义：
 *       前者是"这个板块当前没有成分"，后者是"没有这个板块"，调用方的处理也不同。</li>
 * </ul>
 */
final class StubSectorProvider implements SectorProvider {

  /** 一级大类。 */
  static final String GROUP_ID = "stub-bk-group";

  /** 二级行业，父级为 {@link #GROUP_ID}。 */
  static final String INDUSTRY_ID = "stub-bk-industry";

  /** 存在但没有成分关系的板块。 */
  static final String EMPTY_ID = "stub-bk-empty";

  private static final String SUPPORTED_MARKET = "CN";

  private static final List<Sector> SECTORS = List.of(
      new Sector(GROUP_ID, "STUBG1", "桩大类", SectorType.INDUSTRY.code(),
          null, 1, Sector.STATUS_ACTIVE),
      new Sector(INDUSTRY_ID, "STUBI1", "桩行业", SectorType.INDUSTRY.code(),
          GROUP_ID, 2, Sector.STATUS_ACTIVE),
      new Sector(EMPTY_ID, "STUBE1", "桩空板块", SectorType.CONCEPT.code(),
          null, 1, Sector.STATUS_ACTIVE));

  private final Map<String, List<SectorMember>> memberships;

  private StubSectorProvider(Map<String, List<SectorMember>> memberships) {
    this.memberships = memberships;
  }

  /**
   * @param securityIdsBySectorId 板块 ID → 成分证券 ID，给出的顺序即关系顺序
   */
  static StubSectorProvider of(Map<String, List<String>> securityIdsBySectorId) {
    Map<String, List<SectorMember>> grouped = new LinkedHashMap<>();
    securityIdsBySectorId.forEach((sectorId, securityIds) -> {
      List<SectorMember> members = new ArrayList<>(securityIds.size());
      for (String securityId : securityIds) {
        members.add(new SectorMember(
            securityId, sectorId, SectorMember.RELATION_PRIMARY, true, null, null));
      }
      grouped.put(sectorId, List.copyOf(members));
    });
    return new StubSectorProvider(Map.copyOf(grouped));
  }

  /** 没有任何成分关系的桩源。 */
  static StubSectorProvider empty() {
    return new StubSectorProvider(Map.of());
  }

  @Override
  public List<Sector> findAll(String marketCode) {
    return SUPPORTED_MARKET.equals(marketCode) ? SECTORS : List.of();
  }

  @Override
  public Map<String, List<SectorMember>> memberships(String marketCode, LocalDate effectiveDate) {
    return SUPPORTED_MARKET.equals(marketCode) ? memberships : Map.of();
  }
}
