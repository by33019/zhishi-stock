package cn.zhishi.stock.ai.domain;

import cn.zhishi.stock.market.application.MarketDataUnavailableException;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteBatch;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.news.domain.NewsEvidence;
import cn.zhishi.stock.news.domain.NewsEvidenceProvider;
import cn.zhishi.stock.news.domain.NewsMarketTargets;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.news.domain.NewsType;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 任务上下文构建器：把行情、板块与资讯取一次数，固化成 {@link AiContextSnapshot}
 * 与 {@link AiEvidenceCandidate}。
 *
 * <h2>"冻结"是唯一的要点</h2>
 * 上下文在**任务开始时**取一次并固化。之后行情再怎么变，这份报告引用的仍是固化时看到的事实。
 * 不冻结的话，一份 30 秒后才生成完的报告会引用"生成时刻"的行情，
 * 而正文里写的是"分析区间内"——两者对不上，且不会报错。
 *
 * <h2>不重算任何口径</h2>
 * 行情走 {@link QuoteSnapshotBatchProvider}（整批，端口不接收筛选条件，
 * 于是"所有标的同一快照版本"由接口形状保证）；市场总览走 {@code MarketOverviewQueryService}
 * （MKT-01 的口径）；板块走 {@link SectorProvider}（主数据与成分关系）；资讯走
 * {@link NewsEvidenceProvider}（资讯域自己的可见性判据）。**本类不派生任何统计量**——
 * 派生量一旦在这里重算，就会与页面上的同一指标分叉，而分叉不会报错。
 *
 * <h2>降级不失败</h2>
 * 缺行情、缺资讯、板块不在主数据里，都只记 {@code limitations} 并跳过该条快照，
 * 不抛异常。唯一的"失败"信号是 {@link AiContextBuildResult#coreDataAvailable()}——
 * 契约 §13.5 要求核心行情缺失时拒绝创建任务，而"拒绝"发生在用例层（AI-03），
 * 不在构建器里。
 */
public class AiContextBuilder {

    private static final String SOURCE_SECURITY = "SECURITY";
    private static final String SOURCE_SECTOR = "SECTOR";
    private static final String SOURCE_NEWS = "NEWS";

    private final QuoteSnapshotBatchProvider quoteSnapshots;
    private final MarketOverviewQueryService marketOverview;
    private final SectorProvider sectors;
    private final NewsEvidenceProvider newsEvidence;
    private final AiContentHasher hasher;
    private final int newsEvidenceLimit;

    public AiContextBuilder(
            QuoteSnapshotBatchProvider quoteSnapshots,
            MarketOverviewQueryService marketOverview,
            SectorProvider sectors,
            NewsEvidenceProvider newsEvidence,
            AiContentHasher hasher,
            int newsEvidenceLimit) {
        if (newsEvidenceLimit < 1) {
            throw new IllegalArgumentException("newsEvidenceLimit 必须为正数：" + newsEvidenceLimit);
        }
        this.quoteSnapshots = quoteSnapshots;
        this.marketOverview = marketOverview;
        this.sectors = sectors;
        this.newsEvidence = newsEvidence;
        this.hasher = hasher;
        this.newsEvidenceLimit = newsEvidenceLimit;
    }

    /**
     * 固化上下文。
     *
     * @param targets 已解析的目标（解析与场景规则校验由用例层完成）
     * @param startAt 分析区间起点；{@code null} 表示不设下界
     * @param endAt   分析区间终点；{@code null} 表示不设上界
     */
    public AiContextBuildResult build(
            List<AiContextTarget> targets, OffsetDateTime startAt, OffsetDateTime endAt) {
        List<AiContextSnapshot> snapshots = new ArrayList<>();
        List<AiEvidenceCandidate> evidence = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        boolean coreDataAvailable = true;

        // 整批取数一次：批次属性（版本、数据时间）在循环外取，避免每个标的各取一次批次
        QuoteBatch batch = QuoteBatch.of(quoteSnapshots.fetchBatch(NewsMarketTargets.CN));

        for (AiContextTarget target : targets) {
            switch (target.targetType()) {
                case SECURITY -> {
                    Optional<QuoteSnapshot> snapshot = batch.snapshotOf(target.targetId());
                    if (snapshot.isEmpty()) {
                        coreDataAvailable = false;
                        limitations.add("证券 " + target.targetCode()
                                + " 在当前行情批次中缺少快照，核心行情不完整");
                    } else {
                        appendSecurityQuote(snapshots, evidence, target, snapshot.get());
                    }
                }
                case SECTOR -> {
                    if (!appendSector(snapshots, evidence, target, batch, limitations)) {
                        coreDataAvailable = false;
                    }
                }
                case MARKET -> {
                    Optional<MarketOverview> overview = overviewOf(target.targetId());
                    if (overview.isEmpty()) {
                        coreDataAvailable = false;
                        limitations.add("市场总览数据不可用，核心行情缺失");
                    } else {
                        appendMarketQuote(snapshots, evidence, target, overview.get());
                    }
                }
            }
            appendNews(snapshots, evidence, target, startAt, endAt, limitations);
        }
        return new AiContextBuildResult(snapshots, evidence, limitations, coreDataAvailable);
    }

    // ---------- 行情 ----------

    private void appendSecurityQuote(
            List<AiContextSnapshot> snapshots,
            List<AiEvidenceCandidate> evidence,
            AiContextTarget target,
            QuoteSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("securityId", snapshot.security().securityId());
        data.put("securityCode", snapshot.security().securityCode());
        data.put("securityName", snapshot.security().securityName());
        data.put("exchangeCode", snapshot.security().exchangeCode());
        data.put("isSt", snapshot.security().isSt());
        data.put("isSuspended", snapshot.security().isSuspended());
        data.put("previousClosePrice", snapshot.previousClosePrice());
        data.put("latestPrice", snapshot.latestPrice());
        data.put("changeAmount", snapshot.changeAmount());
        data.put("changeRate", snapshot.changeRate());
        data.put("tradeVolume", snapshot.tradeVolume());
        data.put("tradeAmount", snapshot.tradeAmount());
        data.put("turnoverRate", snapshot.turnoverRate());
        data.put("dataStatus", snapshot.dataStatus().name());
        // dataTime 必须进内容：它是"这批数据是哪一刻的"，少了它哈希就对时间不敏感，
        // 两个不同批次的同一只证券会被判定成"同一份事实"
        data.put("dataTime", Objects.toString(snapshot.dataTime(), null));

        OffsetDateTime cutoff = snapshot.dataTime();
        snapshots.add(new AiContextSnapshot(
                snapshots.size() + 1,
                AiContextType.QUOTE,
                SOURCE_SECURITY,
                target.storageId(),
                target.targetId(),
                cutoff,
                cutoff,
                hasher.hashOf(data),
                data,
                true));

        // 摘要里的每个数字都来自快照本身，不在这里算新指标
        String summary = snapshot.security().securityName()
                + "（" + snapshot.security().securityCode() + "）最新价 " + snapshot.latestPrice()
                + "，涨跌 " + snapshot.changeAmount() + "（" + snapshot.changeRate() + "），"
                + "成交量 " + snapshot.tradeVolume() + " 股，成交额 " + snapshot.tradeAmount()
                + " 元，换手率 " + snapshot.turnoverRate() + "。";
        evidence.add(new AiEvidenceCandidate(
                evidence.size() + 1,
                AiEvidenceType.QUOTE,
                SOURCE_SECURITY,
                target.storageId(),
                target.targetName() + " 行情快照",
                null,
                summary,
                null,
                cutoff,
                AiEvidenceAccessStatus.AVAILABLE,
                hasher.hashOfText(summary)));
    }

    /**
     * 市场级行情上下文：原样投影 MKT-01 的总览口径。
     *
     * <p>不在这里重算广度或成交额——那正是 {@code MarketOverviewQueryService} 的职责，
     * 而"页面上的广度"与"AI 说的广度"必须是同一个数。
     */
    private void appendMarketQuote(
            List<AiContextSnapshot> snapshots,
            List<AiEvidenceCandidate> evidence,
            AiContextTarget target,
            MarketOverview overview) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("marketCode", overview.marketCode());
        data.put("marketStatus", overview.marketStatus().name());
        data.put("tradeDate", Objects.toString(overview.tradeDate(), null));
        data.put("dataStatus", overview.dataStatus().name());
        data.put("snapshotVersion", overview.snapshotVersion());

        List<Map<String, Object>> indices = new ArrayList<>();
        for (MarketOverview.MarketIndex index : orEmpty(overview.indices())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("indexCode", index.indexCode());
            row.put("indexName", index.indexName());
            row.put("latestPoint", index.latestPoint());
            row.put("changeAmount", index.changeAmount());
            row.put("changeRate", index.changeRate());
            row.put("region", index.region() == null ? null : index.region().name());
            indices.add(row);
        }
        data.put("indices", indices);

        MarketOverview.BreadthData breadth = overview.breadth();
        if (breadth != null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("riseCount", breadth.riseCount());
            row.put("fallCount", breadth.fallCount());
            row.put("flatCount", breadth.flatCount());
            row.put("suspendedCount", breadth.suspendedCount());
            row.put("limitUpCount", breadth.limitUpCount());
            row.put("limitDownCount", breadth.limitDownCount());
            row.put("totalCount", breadth.totalCount());
            data.put("breadth", row);
        }

        MarketOverview.TurnoverData turnover = overview.turnover();
        if (turnover != null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("amount", turnover.amount());
            row.put("previousAmount", turnover.previousAmount());
            data.put("turnover", row);
        }

        OffsetDateTime cutoff = overview.dataTime();
        snapshots.add(new AiContextSnapshot(
                snapshots.size() + 1,
                AiContextType.QUOTE,
                "MARKET",
                target.storageId(),
                target.targetId(),
                cutoff,
                cutoff,
                hasher.hashOf(data),
                data,
                true));

        String summary = "市场 " + overview.marketCode()
                + " 截至 " + cutoff
                + " 的行情快照，快照版本 " + overview.snapshotVersion()
                + (breadth == null ? "。" : "，上涨 " + breadth.riseCount() + " 家、下跌 "
                        + breadth.fallCount() + " 家、涨停 " + breadth.limitUpCount() + " 家。");
        evidence.add(new AiEvidenceCandidate(
                evidence.size() + 1,
                AiEvidenceType.QUOTE,
                "MARKET",
                target.storageId(),
                target.targetName() + " 市场总览",
                null,
                summary,
                null,
                cutoff,
                AiEvidenceAccessStatus.AVAILABLE,
                hasher.hashOfText(summary)));
    }

    // ---------- 板块 ----------

    /**
     * 板块上下文：主数据 + 成分关系。
     *
     * <p>**刻意不含**板块涨跌幅、成交额等派生量：那是 {@code SectorQuoteCalculator}
     * 的口径（SEC-02/03 与首页板块预览共用），在这里重算会让"AI 说的板块涨幅"
     * 与"板块页显示的涨幅"有机会不一致，而这种不一致不会报错。
     *
     * <p>截止时间取当前行情批次的时间：批次缺失时板块上下文没有分析价值
     * （它本来就是"在这批行情下看这个板块"），因此不产出快照。
     *
     * @return 是否成功固化了板块上下文（{@code false} 表示核心数据不完整）
     */
    private boolean appendSector(
            List<AiContextSnapshot> snapshots,
            List<AiEvidenceCandidate> evidence,
            AiContextTarget target,
            QuoteBatch batch,
            List<String> limitations) {
        Sector sector = sectors.findAll(NewsMarketTargets.CN).stream()
                .filter(candidate -> candidate.sectorId().equals(target.targetId()))
                .findFirst()
                .orElse(null);
        if (sector == null) {
            limitations.add("板块 " + target.targetCode() + " 不在当前板块主数据中");
            return false;
        }
        OffsetDateTime cutoff = batch.dataTime();
        if (cutoff == null) {
            limitations.add("行情批次缺失，板块 " + target.targetCode() + " 的上下文未固化");
            return false;
        }
        List<SectorMember> members = sectors.memberships(NewsMarketTargets.CN, null)
                .getOrDefault(target.targetId(), List.of());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sectorId", sector.sectorId());
        data.put("sectorCode", sector.sectorCode());
        data.put("sectorName", sector.sectorName());
        data.put("sectorType", sector.sectorType());
        data.put("levelNo", sector.levelNo());
        data.put("memberCount", members.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SectorMember member : members) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("securityId", member.securityId());
            row.put("relationType", member.relationType());
            row.put("isPrimary", member.isPrimary());
            rows.add(row);
        }
        data.put("members", rows);

        snapshots.add(new AiContextSnapshot(
                snapshots.size() + 1,
                AiContextType.SECTOR,
                SOURCE_SECTOR,
                target.storageId(),
                target.targetId(),
                cutoff,
                cutoff,
                hasher.hashOf(data),
                data,
                true));

        String summary = "板块 " + sector.sectorName() + "（" + sector.sectorCode()
                + "，类型 " + sector.sectorType() + "）当前有 " + members.size() + " 只成分股。";
        evidence.add(new AiEvidenceCandidate(
                evidence.size() + 1,
                AiEvidenceType.SECTOR,
                SOURCE_SECTOR,
                target.storageId(),
                target.targetName(),
                null,
                summary,
                null,
                cutoff,
                AiEvidenceAccessStatus.AVAILABLE,
                hasher.hashOfText(summary)));
        return true;
    }

    // ---------- 资讯 ----------

    /**
     * 资讯证据：同一目标共用**一条** NEWS 快照，每条资讯各产出一个证据候选。
     *
     * <p>快照与证据的粒度刻意不同：快照回答"这个目标在区间内有哪些资讯"（渲染 Prompt 用），
     * 证据回答"报告的第 N 号引用指向哪条资讯"（引用校验用）。合成一种会让其中一边别扭。
     *
     * <p>截止时间取该目标**最新一条资讯的发布时间**，而不是"此刻"：
     * 没有资讯时就不产出快照，因此不存在"用 now 填充"的编造窗口。
     */
    private void appendNews(
            List<AiContextSnapshot> snapshots,
            List<AiEvidenceCandidate> evidence,
            AiContextTarget target,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            List<String> limitations) {
        List<NewsEvidence> items = newsEvidence.evidenceFor(
                newsTargetTypeOf(target.targetType()),
                target.targetId(),
                startAt,
                endAt,
                newsEvidenceLimit);
        if (items.isEmpty()) {
            limitations.add(target.targetName() + " 在分析区间内没有可用资讯，报告将为受限分析");
            return;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        OffsetDateTime latest = null;
        for (NewsEvidence item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("newsId", Long.toString(item.newsId()));
            row.put("newsType", item.newsType().name());
            row.put("title", item.title());
            row.put("summary", item.summary());
            row.put("sourceName", item.sourceName());
            row.put("publishedAt", Objects.toString(item.publishedAt(), null));
            rows.add(row);

            if (latest == null || item.publishedAt().isAfter(latest)) {
                latest = item.publishedAt();
            }

            evidence.add(new AiEvidenceCandidate(
                    evidence.size() + 1,
                    evidenceTypeOf(item.newsType()),
                    SOURCE_NEWS,
                    item.newsId(),
                    item.title(),
                    item.originalUrl(),
                    item.summary(),
                    item.publishedAt(),
                    item.publishedAt(),
                    accessStatusOf(item.originalAccessStatus()),
                    hasher.hashOfText(item.title() + "\u0000" + item.summary())));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetId", target.targetId());
        data.put("newsCount", items.size());
        data.put("items", rows);

        snapshots.add(new AiContextSnapshot(
                snapshots.size() + 1,
                AiContextType.NEWS,
                SOURCE_NEWS,
                target.storageId(),
                target.targetId(),
                latest,
                latest,
                hasher.hashOf(data),
                data,
                true));
    }

    // ---------- 映射与辅助 ----------

    private Optional<MarketOverview> overviewOf(String marketCode) {
        try {
            return Optional.of(marketOverview.getOverview(marketCode));
        } catch (MarketDataUnavailableException exception) {
            // 总览不可用是数据侧的事实，不是构建器的故障：记降级、标记核心数据不完整
            return Optional.empty();
        }
    }

    private static NewsTargetType newsTargetTypeOf(AiTargetType targetType) {
        return switch (targetType) {
            case MARKET -> NewsTargetType.MARKET;
            case SECTOR -> NewsTargetType.SECTOR;
            case SECURITY -> NewsTargetType.SECURITY;
        };
    }

    /**
     * 资讯类型 → 证据类型的映射。
     *
     * <p>研报与其他类型归入 {@code NEWS}：{@code ai_evidence.evidence_type} 的取值域里
     * 没有 {@code RESEARCH}，为它新造一个取值会同时改表约束与前端展示。
     * 公告单列是因为它的可信度与解读方式与媒体报道不同。
     */
    private static AiEvidenceType evidenceTypeOf(NewsType newsType) {
        return newsType == NewsType.ANNOUNCEMENT
                ? AiEvidenceType.ANNOUNCEMENT
                : AiEvidenceType.NEWS;
    }

    /**
     * 原文访问状态 → 证据访问状态的映射。
     *
     * <p>{@code UNKNOWN} 归入 {@code AVAILABLE}：这两个枚举回答的问题不同——
     * 前者是"原文链接此刻能不能打开"，后者是"这份证据在报告里允许展示到什么程度"。
     * 摘要已经过了授权闸门，所以展示程度是 {@code AVAILABLE}；
     * "原文状态未知"只影响要不要给链接，那是 {@code sourceUrl} 与前端的事。
     */
    private static AiEvidenceAccessStatus accessStatusOf(NewsOriginalAccessStatus status) {
        return status == NewsOriginalAccessStatus.UNAVAILABLE
                ? AiEvidenceAccessStatus.UNAVAILABLE
                : AiEvidenceAccessStatus.AVAILABLE;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
