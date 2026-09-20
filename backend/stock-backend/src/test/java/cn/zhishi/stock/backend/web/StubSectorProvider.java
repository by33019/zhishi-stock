package cn.zhishi.stock.backend.web;

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
 * 契约测试用的桩板块源。
 *
 * <p>契约测试只关心"JSON 形状、字段名、状态码与查询参数绑定"，因此板块数据必须**手写且极小**：
 * 若复用 {@code SimulatedSectorProvider}，断言里就会出现随模拟算法漂移的板块编号与成分数量，
 * 而这些数字与契约无关。真实模拟源的行为由 {@code SimulatedSectorProviderTest} 覆盖。
 *
 * <p>五个板块覆盖五种状态，缺一个就会有分支测不到：
 * <ul>
 *   <li>{@link #GROUP_ID} / {@link #INDUSTRY_ID}：一级与二级的父子关系（SEC-03 的 {@code parent}）</li>
 *   <li>{@link #INACTIVE_ID}：停用板块（SEC-01 默认不返回、SEC-03 返回 200、SEC-04/06 报 404）</li>
 *   <li>{@link #EMPTY_ID}：存在但无成分关系（SEC-06 报 404，不是空页）</li>
 *   <li>{@link #HALTED_ID}：成分全部停牌（SEC-04 报 503，且不进涨幅榜）</li>
 * </ul>
 */
final class StubSectorProvider implements SectorProvider {

  static final String GROUP_ID = "stub-bk-group";
  static final String INDUSTRY_ID = "stub-bk-industry";
  static final String INACTIVE_ID = "stub-bk-inactive";
  static final String EMPTY_ID = "stub-bk-empty";
  static final String HALTED_ID = "stub-bk-halted";

  private static final String SUPPORTED_MARKET = "CN";

  private static final List<Sector> SECTORS = List.of(
      new Sector(GROUP_ID, "STUB0001", "桩大类", SectorType.INDUSTRY.code(),
          null, 1, Sector.STATUS_ACTIVE),
      new Sector(INDUSTRY_ID, "STUB0002", "桩行业", SectorType.INDUSTRY.code(),
          GROUP_ID, 2, Sector.STATUS_ACTIVE),
      new Sector(INACTIVE_ID, "STUB0003", "桩停用板块", SectorType.INDUSTRY.code(),
          null, 1, Sector.STATUS_INACTIVE),
      new Sector(EMPTY_ID, "STUB0004", "桩空板块", SectorType.CONCEPT.code(),
          null, 1, Sector.STATUS_ACTIVE),
      new Sector(HALTED_ID, "STUB0005", "桩全停牌板块", SectorType.CONCEPT.code(),
          null, 1, Sector.STATUS_ACTIVE));

  private final Map<String, List<SectorMember>> memberships;

  private StubSectorProvider(Map<String, List<SectorMember>> memberships) {
    this.memberships = memberships;
  }

  /** @param securityIdsBySectorId 板块 ID → 成分证券 ID，给出的顺序即关系顺序 */
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
