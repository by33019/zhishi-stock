package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SectorType;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性模拟板块主数据与成分关系。
 *
 * <p>板块**不重复定义证券全集**，成分关系投影自 {@link SecurityMasterProvider}：
 * 代码段一旦在两处各写一遍，"板块成分"与"广度计数"就会指向不同的证券全集，
 * 且没有任何测试会红。
 *
 * <p>归属只由 {@code securityCode} 的哈希导出，**与列表顺序无关**：
 * 用"在全集中的序号"决定归属的话，生成顺序一变，同一只证券就会悄悄换板块。
 *
 * <p>板块名称是合成数据集的一部分，与 {@code 模拟证券600000} 同性质——
 * 全部为模拟数据，不代表任何真实行业分类。
 *
 * <h2>板块构成（39 个）</h2>
 * <ul>
 *   <li>5 个一级大类（{@code levelNo = 1}，{@code parentId = null}）</li>
 *   <li>20 个二级行业（{@code levelNo = 2}，{@code parentId} 指向所属大类）</li>
 *   <li>8 个概念板块、6 个地域板块</li>
 * </ul>
 *
 * <p>编号顺序为「大类 → 行业 → 概念 → 地域」，父级先于子级出现，
 * 因此 {@code sectorCode} 的字典序与层级顺序一致。
 */
public class SimulatedSectorProvider implements SectorProvider {

  private static final String SUPPORTED_MARKET = "CN";
  private static final String ID_PREFIX = "sim-bk";
  private static final String CODE_PREFIX = "BK";

  /** 关系生效起点。取与 {@code SimulatedSecurityQuoteProvider.EARLIEST_LISTING} 同日，表示"自最早上市起"。 */
  private static final LocalDate RELATION_EFFECTIVE_FROM = LocalDate.of(2010, 1, 4);

  private static final int INDUSTRY_COUNT = 20;
  private static final int CONCEPT_COUNT = 8;
  private static final int REGION_COUNT = 6;

  /** 概念板块覆盖率（百分之一为单位）。 */
  private static final long CONCEPT_COVERAGE_PERCENT = 25;

  private static final int SALT_INDUSTRY = 11;
  private static final int SALT_CONCEPT_PRESENCE = 23;
  private static final int SALT_CONCEPT_INDEX = 29;
  private static final int SALT_REGION = 37;

  /** 一级大类 → 下属二级行业。声明顺序即 {@code sectorId} 分配顺序。 */
  private static final List<IndustryGroup> GROUPS = List.of(
      new IndustryGroup("金融", List.of("银行", "证券", "保险")),
      new IndustryGroup("科技", List.of("半导体", "消费电子", "软件服务", "通信设备")),
      new IndustryGroup("制造与周期", List.of("电力设备", "汽车整车", "基础化工", "有色金属", "煤炭开采", "石油石化")),
      new IndustryGroup("消费与医药", List.of("医药生物", "医疗器械", "食品饮料", "家用电器")),
      new IndustryGroup("民生与公用", List.of("房地产开发", "交通运输", "公用事业")));

  private static final List<String> CONCEPTS = List.of(
      "人工智能", "国产替代", "高股息", "新能源", "数据中心", "军民融合", "消费复苏", "一带一路");

  private static final List<String> REGIONS = List.of(
      "长三角", "珠三角", "京津冀", "成渝", "中部地区", "东北地区");

  private static final List<Sector> SECTORS;
  private static final List<String> GROUP_SECTOR_IDS;
  private static final List<String> INDUSTRY_SECTOR_IDS;
  private static final List<String> CONCEPT_SECTOR_IDS;
  private static final List<String> REGION_SECTOR_IDS;

  /** 二级行业序号 → 所属大类序号。由 {@link #GROUPS} 展开，避免归属在两处各写一遍。 */
  private static final int[] GROUP_OF_INDUSTRY = new int[INDUSTRY_COUNT];

  static {
    List<Sector> sectors = new ArrayList<>();
    List<String> groupIds = new ArrayList<>();
    List<String> industryIds = new ArrayList<>();
    List<String> conceptIds = new ArrayList<>();
    List<String> regionIds = new ArrayList<>();

    int ordinal = 1;
    for (IndustryGroup group : GROUPS) {
      String id = sectorId(ordinal);
      sectors.add(new Sector(
          id, sectorCode(ordinal), group.name(), SectorType.INDUSTRY.code(),
          null, 1, Sector.STATUS_ACTIVE));
      groupIds.add(id);
      ordinal++;
    }

    int industryIndex = 0;
    for (int groupIndex = 0; groupIndex < GROUPS.size(); groupIndex++) {
      for (String name : GROUPS.get(groupIndex).industries()) {
        String id = sectorId(ordinal);
        sectors.add(new Sector(
            id, sectorCode(ordinal), name, SectorType.INDUSTRY.code(),
            groupIds.get(groupIndex), 2, Sector.STATUS_ACTIVE));
        industryIds.add(id);
        GROUP_OF_INDUSTRY[industryIndex] = groupIndex;
        industryIndex++;
        ordinal++;
      }
    }

    for (String name : CONCEPTS) {
      String id = sectorId(ordinal);
      sectors.add(new Sector(
          id, sectorCode(ordinal), name, SectorType.CONCEPT.code(), null, 1, Sector.STATUS_ACTIVE));
      conceptIds.add(id);
      ordinal++;
    }

    for (String name : REGIONS) {
      String id = sectorId(ordinal);
      sectors.add(new Sector(
          id, sectorCode(ordinal), name, SectorType.REGION.code(), null, 1, Sector.STATUS_ACTIVE));
      regionIds.add(id);
      ordinal++;
    }

    SECTORS = List.copyOf(sectors);
    GROUP_SECTOR_IDS = List.copyOf(groupIds);
    INDUSTRY_SECTOR_IDS = List.copyOf(industryIds);
    CONCEPT_SECTOR_IDS = List.copyOf(conceptIds);
    REGION_SECTOR_IDS = List.copyOf(regionIds);
  }

  private final SecurityMasterProvider securityMasterProvider;

  public SimulatedSectorProvider(SecurityMasterProvider securityMasterProvider) {
    this.securityMasterProvider = securityMasterProvider;
  }

  @Override
  public List<Sector> findAll(String marketCode) {
    return SUPPORTED_MARKET.equals(marketCode) ? SECTORS : List.of();
  }

  /**
   * 按板块分组的成分关系。
   *
   * <p>每只证券的关系条数固定（行业 + 大类 + 地域，概念可有可无），因此一次遍历即可建完索引，
   * 不必为 39 个板块各扫一遍证券全集。
   */
  @Override
  public Map<String, List<SectorMember>> memberships(String marketCode, LocalDate effectiveDate) {
    if (!SUPPORTED_MARKET.equals(marketCode)) {
      return Map.of();
    }
    Map<String, List<SectorMember>> grouped = new LinkedHashMap<>();
    for (SecuritySummary security : securityMasterProvider.findAll(marketCode)) {
      for (SectorMember member : membersOf(security.securityCode())) {
        if (member.effectiveOn(effectiveDate)) {
          grouped.computeIfAbsent(member.sectorId(), key -> new ArrayList<>()).add(member);
        }
      }
    }
    Map<String, List<SectorMember>> frozen = new LinkedHashMap<>();
    grouped.forEach((sectorId, members) -> frozen.put(sectorId, List.copyOf(members)));
    return Map.copyOf(frozen);
  }

  /** 一只证券的全部成分关系：1 个二级行业 + 1 个一级大类 + 0~1 个概念 + 1 个地域。 */
  private static List<SectorMember> membersOf(String securityCode) {
    int industry = (int) Math.floorMod(
        SimulatedHashing.bucket(securityCode, SALT_INDUSTRY), INDUSTRY_COUNT);

    List<SectorMember> members = new ArrayList<>(4);
    members.add(member(
        securityCode, INDUSTRY_SECTOR_IDS.get(industry), SectorMember.RELATION_PRIMARY, true));
    members.add(member(
        securityCode,
        GROUP_SECTOR_IDS.get(GROUP_OF_INDUSTRY[industry]),
        SectorMember.RELATION_SECONDARY,
        false));

    long conceptBucket = Math.floorMod(
        SimulatedHashing.bucket(securityCode, SALT_CONCEPT_PRESENCE), 100);
    if (conceptBucket < CONCEPT_COVERAGE_PERCENT) {
      int concept = (int) Math.floorMod(
          SimulatedHashing.bucket(securityCode, SALT_CONCEPT_INDEX), CONCEPT_COUNT);
      members.add(member(
          securityCode, CONCEPT_SECTOR_IDS.get(concept), SectorMember.RELATION_MEMBER, false));
    }

    int region = (int) Math.floorMod(
        SimulatedHashing.bucket(securityCode, SALT_REGION), REGION_COUNT);
    members.add(member(
        securityCode, REGION_SECTOR_IDS.get(region), SectorMember.RELATION_SECONDARY, false));

    return List.copyOf(members);
  }

  private static SectorMember member(
      String securityCode, String sectorId, String relationType, boolean primary) {
    // 证券 ID 由代码派生：构词规则统一在 SimulatedSecurityIds，不再靠注释维持"两处一致"
    return new SectorMember(
        SimulatedSecurityIds.securityIdOf(securityCode),
        sectorId,
        relationType,
        primary,
        RELATION_EFFECTIVE_FROM,
        null);
  }

  private static String sectorId(int ordinal) {
    return ID_PREFIX + padded(ordinal);
  }

  private static String sectorCode(int ordinal) {
    return CODE_PREFIX + padded(ordinal);
  }

  private static String padded(int value) {
    String digits = Integer.toString(value);
    return "0".repeat(4 - digits.length()) + digits;
  }

  /** 一个一级大类及其下属二级行业。 */
  private record IndustryGroup(String name, List<String> industries) {
  }
}
