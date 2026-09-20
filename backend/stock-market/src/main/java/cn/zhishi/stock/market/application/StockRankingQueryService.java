package cn.zhishi.stock.market.application;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.RankingType;
import cn.zhishi.stock.market.domain.SectorMembershipIndex;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.StockRanking;
import java.util.List;
import java.util.Set;

/**
 * 榜单查询用例（QTE-01）。
 *
 * <h2>为什么排序 / 筛选 / 分页放在这里，而不是交给 Provider</h2>
 * 契约要求"整个榜单使用同一已完成快照版本"。把这三件事留在用例层、让端口只提供整批快照，
 * 这条要求就由**接口形状**决定，而不是依赖实现方记得只取一个批次。
 * 附带好处是排序口径可以脱离数据源测试——用桩批次直接断言即可。
 *
 * <h2>为什么筛选值不校验合法性，口径却必须校验</h2>
 * <ul>
 *   <li>筛选值（{@code exchangeCodes}、{@code sectorId}…）不存在时返回空结果，语义上仍然诚实——
 *       {@code exchangeCodes=SH} 是合法取值，只是当前数据里恰好没有。若报 400，
 *       就把"没有数据"错报成"参数非法"。</li>
 *   <li>{@code rankingType} 被静默忽略时，调用方拿到的是"榜单类型不对但看起来正常"的响应，
 *       极难排查。因此白名单外的取值直接报错，不回落默认榜单。</li>
 * </ul>
 */
public class StockRankingQueryService {

  /** 榜单是市场无关的单一横截面；接口没有 marketCode 参数，内部固定为 CN。 */
  private static final String MARKET_CODE = "CN";

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;

  /** 停牌证券默认排除（PRD §7.3 QTE-02「停牌和无有效价格默认排除」）。 */
  private static final boolean DEFAULT_EXCLUDE_SUSPENDED = true;

  /** ST 证券默认**不**排除：PRD 未给出默认值，而"是否回避 ST"是投资者的主动选择。 */
  private static final boolean DEFAULT_EXCLUDE_ST = false;

  private final QuoteSnapshotBatchProvider batchProvider;
  private final SectorProvider sectorProvider;

  public StockRankingQueryService(
      QuoteSnapshotBatchProvider batchProvider, SectorProvider sectorProvider) {
    this.batchProvider = batchProvider;
    this.sectorProvider = sectorProvider;
  }

  /** 返回榜单的一页。 */
  public StockRanking rank(RankingCriteria criteria) {
    RankingCriteria effective = criteria == null ? RankingCriteria.empty() : criteria;
    RankingType type = resolveType(effective.rankingType());
    int page = validatePage(effective.page());
    int size = validatePageSize(effective.size());
    Set<String> exchanges = QueryParameters.parseCsvFilter(effective.exchangeCodes());
    Set<String> boards = QueryParameters.parseCsvFilter(effective.boardCodes());
    boolean excludeSt = effective.excludeSt() == null ? DEFAULT_EXCLUDE_ST : effective.excludeSt();
    boolean excludeSuspended = effective.excludeSuspended() == null
        ? DEFAULT_EXCLUDE_SUSPENDED
        : effective.excludeSuspended();

    // 板块条件为空表示不参与筛选；为空串按"未传"处理（同 exchangeCodes 的解析口径）。
    // 两个局部变量都是有效 final，可以安全地在 lambda 里引用。
    String sectorId = QueryParameters.isPresent(effective.sectorId())
        ? effective.sectorId().trim()
        : null;
    Set<String> sectorMembers = sectorId == null
        ? Set.of()
        : SectorMembershipIndex.securityIdsOf(
            sectorProvider.memberships(MARKET_CODE, null), sectorId);

    QuoteBatch batch = QuoteBatch.of(batchProvider.fetchBatch(MARKET_CODE));

    List<QuoteSnapshot> ranked = batch.snapshots().stream()
        .filter(snapshot -> matches(snapshot, exchanges, boards, excludeSt, excludeSuspended))
        .filter(snapshot -> sectorId == null || sectorMembers.contains(snapshot.security().securityId()))
        .filter(type::hasSortKey)
        .sorted(type.order())
        .toList();

    PageData<QuoteSnapshot> sliced = PageData.slice(ranked, page, size);
    return new StockRanking(
        sliced.items(),
        sliced.page(),
        sliced.size(),
        sliced.total(),
        sliced.totalPages(),
        sliced.hasNext(),
        type.code(),
        batch.version(),
        batch.dataTime(),
        batch.dataStatus());
  }

  // ---------- 筛选 ----------

  private static boolean matches(
      QuoteSnapshot snapshot,
      Set<String> exchanges,
      Set<String> boards,
      boolean excludeSt,
      boolean excludeSuspended) {
    SecuritySummary security = snapshot.security();
    if (!exchanges.isEmpty() && !exchanges.contains(QueryParameters.lower(security.exchangeCode()))) {
      return false;
    }
    if (!boards.isEmpty() && !boards.contains(QueryParameters.lower(security.boardCode()))) {
      return false;
    }
    if (excludeSt && security.isSt()) {
      return false;
    }
    return !(excludeSuspended && security.isSuspended());
  }

  // ---------- 校验 ----------

  private static RankingType resolveType(String raw) {
    return RankingType.fromCode(raw).orElseThrow(() -> new InvalidRankingQueryException(
        "rankingType 必须为 " + String.join("、", RankingType.codes()) + " 之一"));
  }

  private static int validatePage(Integer page) {
    if (page == null) {
      return DEFAULT_PAGE;
    }
    if (page < 1) {
      throw new InvalidRankingQueryException("page 必须大于等于 1");
    }
    return page;
  }

  private static int validatePageSize(Integer size) {
    if (size == null) {
      return DEFAULT_PAGE_SIZE;
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidRankingQueryException("size 必须为 1 至 100");
    }
    return size;
  }
}
