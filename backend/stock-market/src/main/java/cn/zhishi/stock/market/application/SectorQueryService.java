package cn.zhishi.stock.market.application;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.QuoteBatch;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.RankingType;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorConstituent;
import cn.zhishi.stock.market.domain.SectorDetail;
import cn.zhishi.stock.market.domain.SectorList;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SectorQuote;
import cn.zhishi.stock.market.domain.SectorQuoteCalculator;
import cn.zhishi.stock.market.domain.SectorType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 板块查询用例：主数据（SEC-01）、详情（SEC-03）、行情统计（SEC-04）、成分股（SEC-06）。
 *
 * <p>四个用例合在一个服务里，是因为它们的取数路径完全相同——"取成分关系 → 取整批快照 →
 * 按关系挑出成分股"——差别只在最后一步是"直接返回统计"、"附带板块主数据"还是"分页列出成分"。
 * 拆成四个服务会让这条路径写四遍。
 *
 * <h2>三种"拿不到数据"的语义分得很开</h2>
 * <ul>
 *   <li>板块 ID 不存在 → 404 {@code SECTOR_NOT_FOUND}</li>
 *   <li>板块已停用 → SEC-03 仍返回 200（契约：「已停用板块可返回历史状态」），
 *       SEC-04 / SEC-06 返回 404 {@code SECTOR_INACTIVE}（"当前行情"与"当前成分"要求板块有效）</li>
 *   <li>板块有成分但没有任何可统计行情 → SEC-04 返回 503 {@code SECTOR_QUOTE_NOT_AVAILABLE}；
 *       SEC-03 照常返回（缺成分是数据质量问题，用 {@code companyCount} 表达比报错更可诊断）</li>
 * </ul>
 */
public class SectorQueryService {

    /** 板块是市场无关的单一分类体系；接口没有 marketCode 参数，内部固定为 CN。 */
    private static final String MARKET_CODE = "CN";

    private final SectorProvider sectorProvider;
    private final QuoteSnapshotBatchProvider batchProvider;

    public SectorQueryService(SectorProvider sectorProvider, QuoteSnapshotBatchProvider batchProvider) {
        this.sectorProvider = sectorProvider;
        this.batchProvider = batchProvider;
    }

    /**
     * SEC-01：板块主数据列表。
     *
     * <p>不排序：{@link SectorProvider#findAll} 给出的顺序是"大类 → 行业 → 概念 → 地域"，
     * 本身就是有意义的展示顺序，再排一次反而会让父级与子级分开。
     */
    public SectorList list(SectorCriteria criteria) {
        SectorCriteria effective = criteria == null ? SectorCriteria.empty() : criteria;
        Optional<SectorType> type = SectorParameters.sectorType(effective.sectorType());
        String status = SectorParameters.status(effective.status());
        String parentId = trimmed(effective.parentId());
        String keyword = trimmed(effective.keyword());

        List<Sector> sectors = sectorProvider.findAll(MARKET_CODE).stream()
                .filter(sector -> type.map(sector::isType).orElse(true))
                .filter(sector -> status.equalsIgnoreCase(sector.status()))
                .filter(sector -> parentId == null || parentId.equals(sector.parentId()))
                .filter(sector -> keyword == null || matchesKeyword(sector, keyword))
                .toList();
        return new SectorList(sectors);
    }

    /** SEC-03：板块详情头部。停用板块仍返回 200。 */
    public SectorDetail detail(String sectorId) {
        Sector sector = require(sectorId);
        return new SectorDetail(sector, parentOf(sector), statistics(sector));
    }

    /** SEC-04：板块最新行情统计。 */
    public SectorQuote quote(String sectorId) {
        Sector sector = require(sectorId);
        if (!sector.active()) {
            throw SectorNotFoundException.inactive(sectorId);
        }
        SectorQuote quote = statistics(sector);
        if (quote.averagePrice() == null) {
            // 有成分但全停牌（或成分关系为空）：没有"平均涨跌幅"可言
            throw new SectorQuoteNotAvailableException(sectorId);
        }
        return quote;
    }

    /**
     * SEC-06：板块成分股。
     *
     * <p>**不按排序键过滤**：调用方问的是"这个板块里有什么"，把停牌成分悄悄去掉会让
     * 列表条数与 {@code companyCount} 对不上。无有效行情的行按代码升序排在末尾
     * （PRD §7.3 QTE-03：「部分成交额缺失时排在末尾并标记缺失」）。
     */
    public PageData<SectorConstituent> constituents(String sectorId, ConstituentCriteria criteria) {
        ConstituentCriteria effective = criteria == null ? ConstituentCriteria.empty() : criteria;
        int page = SectorParameters.page(effective.page());
        int size = SectorParameters.pageSize(effective.size());
        RankingType rankingType = SectorParameters.rankingType(effective.rankingType())
                .orElse(SectorParameters.DEFAULT_RANKING_TYPE);
        LocalDate effectiveDate = SectorParameters.effectiveDate(effective.effectiveDate());

        Sector sector = require(sectorId);
        if (!sector.active()) {
            throw SectorNotFoundException.inactive(sectorId);
        }

        List<SectorMember> members = memberships(effectiveDate).getOrDefault(sectorId, List.of());
        if (members.isEmpty()) {
            throw SectorNotFoundException.constituentsMissing(sectorId);
        }

        QuoteBatch batch = QuoteBatch.of(batchProvider.fetchBatch(MARKET_CODE));
        List<QuoteSnapshot> quotes = batch.ofMembers(members);
        Map<String, Integer> ranks = SectorQuoteCalculator.contributionRanks(quotes);

        List<SectorConstituent> items = new ArrayList<>(members.size());
        for (SectorMember member : members) {
            Optional<QuoteSnapshot> snapshot = batch.snapshotOf(member.securityId());
            if (snapshot.isEmpty()) {
                continue;
            }
            items.add(new SectorConstituent(
                    snapshot.get(),
                    member.relationType(),
                    member.isPrimary(),
                    ranks.getOrDefault(member.securityId(), 0)));
        }
        return PageData.slice(order(items, rankingType), page, size);
    }

    // ---------- 统计 ----------

    private SectorQuote statistics(Sector sector) {
        QuoteBatch batch = QuoteBatch.of(batchProvider.fetchBatch(MARKET_CODE));
        List<QuoteSnapshot> constituents = batch.ofMembers(
                memberships(null).getOrDefault(sector.sectorId(), List.of()));
        return SectorQuoteCalculator.calculate(
                sector, constituents, batch.dataTime(), batch.dataStatus());
    }

    private Map<String, List<SectorMember>> memberships(LocalDate effectiveDate) {
        return sectorProvider.memberships(MARKET_CODE, effectiveDate);
    }

    /**
     * 成分股排序：有有效排序键的按口径排在前，其余按代码升序排在末尾。
     *
     * <p>不能直接用 {@link RankingType#order()}——它要求调用方先用 {@code hasSortKey} 过滤，
     * 而成分股列表必须保留无有效行情的成分。
     */
    private static List<SectorConstituent> order(
            List<SectorConstituent> items, RankingType rankingType) {
        List<SectorConstituent> sortable = items.stream()
                .filter(item -> rankingType.hasSortKey(item.quote()))
                .sorted(Comparator.comparing(SectorConstituent::quote, rankingType.order()))
                .toList();
        List<SectorConstituent> unsortable = items.stream()
                .filter(item -> !rankingType.hasSortKey(item.quote()))
                .sorted(Comparator.comparing(item -> item.quote().security().fullSymbol()))
                .toList();

        List<SectorConstituent> ordered = new ArrayList<>(sortable);
        ordered.addAll(unsortable);
        return ordered;
    }

    // ---------- 主数据 ----------

    private Sector require(String sectorId) {
        return sectorProvider.findAll(MARKET_CODE).stream()
                .filter(sector -> sector.sectorId().equals(sectorId))
                .findFirst()
                .orElseThrow(() -> SectorNotFoundException.notFound(sectorId));
    }

    /**
     * 父级板块；不存在时为 {@code null}。
     *
     * <p>父级查不到**不等于报错**：层级关系缺失时仍应能展示该板块本身
     * （PRD §7.4 SEC-02「映射缺失时仍展示可用行情并说明成分股不完整」）。
     */
    private Sector parentOf(Sector sector) {
        if (sector.parentId() == null) {
            return null;
        }
        return sectorProvider.findAll(MARKET_CODE).stream()
                .filter(candidate -> candidate.sectorId().equals(sector.parentId()))
                .findFirst()
                .orElse(null);
    }

    private static boolean matchesKeyword(Sector sector, String keyword) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        return containsIgnoreCase(sector.sectorCode(), needle)
                || containsIgnoreCase(sector.sectorName(), needle);
    }

    private static boolean containsIgnoreCase(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static String trimmed(String value) {
        return QueryParameters.isPresent(value) ? value.trim() : null;
    }
}
