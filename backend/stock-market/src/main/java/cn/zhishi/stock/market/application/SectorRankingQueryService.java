package cn.zhishi.stock.market.application;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.QuoteBatch;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.RankingType;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SectorQuote;
import cn.zhishi.stock.market.domain.SectorQuoteCalculator;
import cn.zhishi.stock.market.domain.SectorRanking;
import cn.zhishi.stock.market.domain.SectorType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 板块排行用例（SEC-02）。
 *
 * <p>与 QTE-01 的 {@link StockRankingQueryService} 同构：取整批快照一次 → 逐个板块聚合 →
 * 筛选 → 排序 → 分页。差别只在"行"是板块而不是证券，以及聚合要按成分关系分组。
 *
 * <p>为什么筛选 / 排序 / 分页同样放在用例层而不是 Provider：契约要求
 * "返回同一快照的板块排行"。让端口只提供整批快照，这条要求就由**接口形状**决定，
 * 而不是依赖实现方记得只取一个批次。
 */
public class SectorRankingQueryService {

    /** 板块是市场无关的单一分类体系；接口没有 marketCode 参数，内部固定为 CN。 */
    private static final String MARKET_CODE = "CN";

    private final SectorProvider sectorProvider;
    private final QuoteSnapshotBatchProvider batchProvider;

    public SectorRankingQueryService(
            SectorProvider sectorProvider, QuoteSnapshotBatchProvider batchProvider) {
        this.sectorProvider = sectorProvider;
        this.batchProvider = batchProvider;
    }

    /** 返回板块排行的一页。 */
    public SectorRanking rank(SectorRankingCriteria criteria) {
        SectorRankingCriteria effective = criteria == null ? SectorRankingCriteria.empty() : criteria;
        RankingType rankingType = SectorParameters.rankingType(effective.rankingType())
                .orElse(SectorParameters.DEFAULT_RANKING_TYPE);
        Optional<SectorType> sectorType = SectorParameters.sectorType(effective.sectorType());
        int page = SectorParameters.page(effective.page());
        int size = SectorParameters.pageSize(effective.size());

        QuoteBatch batch = QuoteBatch.of(batchProvider.fetchBatch(MARKET_CODE));
        Map<String, List<SectorMember>> memberships = sectorProvider.memberships(MARKET_CODE, null);

        List<SectorQuote> ranked = sectorProvider.findAll(MARKET_CODE).stream()
                // 停用板块不进当前排行（契约 §10 SEC-03 说明）
                .filter(Sector::active)
                .filter(sector -> sectorType.map(sector::isType).orElse(true))
                .map(sector -> SectorQuoteCalculator.calculate(
                        sector,
                        batch.ofMembers(memberships.getOrDefault(sector.sectorId(), List.of())),
                        batch.dataTime(),
                        batch.dataStatus()))
                // 无有效排序键的板块不进榜：先过滤再排序，比较器因此不必处理 null
                .filter(rankingType::hasSortKey)
                .sorted(rankingType.sectorOrder())
                .toList();

        PageData<SectorQuote> sliced = PageData.slice(ranked, page, size);
        return new SectorRanking(
                sliced.items(),
                sliced.page(),
                sliced.size(),
                sliced.total(),
                sliced.totalPages(),
                sliced.hasNext(),
                sectorType.map(SectorType::code).orElse(null),
                rankingType.code(),
                batch.version(),
                batch.dataTime(),
                batch.dataStatus());
    }
}
