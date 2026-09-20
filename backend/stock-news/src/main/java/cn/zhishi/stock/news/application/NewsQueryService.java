package cn.zhishi.stock.news.application;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.news.domain.NewsArticle;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsContentStatus;
import cn.zhishi.stock.news.domain.NewsCountProvider;
import cn.zhishi.stock.news.domain.NewsDetail;
import cn.zhishi.stock.news.domain.NewsEvidence;
import cn.zhishi.stock.news.domain.NewsEvidenceProvider;
import cn.zhishi.stock.news.domain.NewsMarketTargets;
import cn.zhishi.stock.news.domain.NewsOptions;
import cn.zhishi.stock.news.domain.NewsPage;
import cn.zhishi.stock.news.domain.NewsRecord;
import cn.zhishi.stock.news.domain.NewsRelation;
import cn.zhishi.stock.news.domain.NewsRelationSummary;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.domain.NewsSourceType;
import cn.zhishi.stock.news.domain.NewsSummary;
import cn.zhishi.stock.news.domain.NewsSyncStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.news.domain.NewsTimeRange;
import cn.zhishi.stock.news.domain.NewsType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NEWS-01~04、STK-10、SEC-07 六个查询接口的业务规则，且**只在这里**。
 *
 * <h2>可见性过滤链（顺序即实现顺序）</h2>
 *
 * <ol>
 *   <li>{@code content_status = PUBLISHED}（契约 §11.2）
 *   <li>{@code dedup_status = ORIGINAL}（契约 §11.2：重复稿折叠到主记录）
 *   <li>来源当前可用（授权状态、运行状态、授权期），走 {@link NewsSource#usableOn}
 *   <li>单条内容的 {@code rights_expire_at} 未过
 *   <li>关联只认 {@code CONFIRMED}（契约 §11.2：低置信候选不进普通列表与 AI 证据）
 *   <li>排序 {@code published_at DESC, news_id DESC}
 * </ol>
 *
 * <p>前四步是**所有**接口共用的，写在 {@link #visible} 一处；各接口的差异只是附加条件。
 * 拆到各接口里各写一遍，就会出现"列表看不到、详情能打开"这类不会报错的分叉。
 *
 * <h2>为什么在用例层过滤而不是下推到 SQL</h2>
 * 与榜单、板块预览同一条理由：整批取数后在用例层筛/排/分页，
 * "可见性判据只写一遍"因此由接口形状保证，而且每条判据都能用极小的桩直接断言。
 * 代价是扫描量随资讯量线性增长——真实源接入时必须下推，索引已经就绪
 * （{@code idx_stock_news_publish}、{@code idx_news_relation_target_status}）。
 */
public class NewsQueryService implements NewsCountProvider, NewsEvidenceProvider {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 50;

    /** 契约 §11.2 的筛选规则，NEWS-04 原样返回给前端做文案依据。 */
    private static final String FILTER_RULES =
            "仅返回已发布的主记录（跨来源重复稿折叠到主记录）、授权有效来源、未过期内容与已确认关联；"
                    + "低置信候选关联不进入列表。";

    private static final String COPYRIGHT_NOTICE_TEMPLATE =
            "内容来源：%s。本页仅展示授权范围内的摘要，完整内容请前往原文。";

    private final NewsArticleStore articles;
    private final NewsSourceStore sources;
    private final SecurityIdentityProvider securityIdentities;
    private final SectorIdentityProvider sectorIdentities;
    private final Clock clock;

    public NewsQueryService(
            NewsArticleStore articles,
            NewsSourceStore sources,
            SecurityIdentityProvider securityIdentities,
            SectorIdentityProvider sectorIdentities,
            Clock clock) {
        this.articles = articles;
        this.sources = sources;
        this.securityIdentities = securityIdentities;
        this.sectorIdentities = sectorIdentities;
        this.clock = clock;
    }

    // ---------- NEWS-01 ----------

    public PageData<NewsSummary> list(NewsQuery query) {
        return page(query);
    }

    /**
     * NEWS-01 的控制器入口：解析原始查询参数，并附上整批资讯的同步元信息。
     *
     * <p>参数一律是原始字符串、在这里解析，与 {@link #bySecurity} / {@link #bySector} 同形。
     * 让控制器自己解析 {@code newsTypes} 会在两个地方各有一份"哪些取值合法"的知识，
     * 而这份知识一旦分叉**不会报错**——只会让某个入口悄悄接受一个不该接受的取值。
     *
     * <p>同步元信息与列表在同一次调用里取：分两次调用（控制器先要列表再要状态）
     * 会让"这一页对应的新鲜度"与"响应里写的新鲜度"之间存在一个可被采集任务插入的窗口。
     */
    public NewsPage browse(
            String newsTypes,
            String securityId,
            String sectorId,
            String marketCode,
            String startAt,
            String endAt,
            String keyword,
            Integer page,
            Integer size) {
        return envelope(NewsQuery.of(
                parseNewsTypes(newsTypes),
                securityId,
                sectorId,
                marketCode,
                parseInstant(startAt, "startAt", false),
                parseInstant(endAt, "endAt", true),
                keyword,
                page,
                size));
    }

    /**
     * 三个列表接口（NEWS-01 / STK-10 / SEC-07）的**唯一出口**：分页结果 + 同源的同步元信息。
     *
     * <p>契约给这三个接口的返回参数都写了"{@code PageData<NewsSummary>}、
     * {@code lastSuccessfulSyncAt}、{@code dataStatus}"，因此封套的拼装只允许有一处——
     * 三个接口各拼一遍，某天只改其中一个就会让个股页与资讯中心的 {@code dataStatus} 语义分叉，
     * 而这种分叉不会报错。
     */
    private NewsPage envelope(NewsQuery query) {
        return NewsPage.of(page(query), syncStatus());
    }

    // ---------- STK-10 ----------

    /** 个股资讯。契约：只返回与股票**确认**关联的资讯，个股资讯不因板块关系泛化。 */
    public NewsPage bySecurity(
            String securityId,
            String newsType,
            String startAt,
            String endAt,
            Integer page,
            Integer size) {
        return envelope(NewsQuery.of(
                parseNewsTypes(newsType),
                securityId,
                null,
                null,
                parseInstant(startAt, "startAt", false),
                parseInstant(endAt, "endAt", true),
                null,
                page,
                size));
    }

    // ---------- SEC-07 ----------

    /** 板块资讯。契约：个股资讯不会因单一成分关系自动泛化为板块事实。 */
    public NewsPage bySector(
            String sectorId,
            String newsType,
            String startAt,
            String endAt,
            Integer page,
            Integer size) {
        return envelope(NewsQuery.of(
                parseNewsTypes(newsType),
                null,
                sectorId,
                null,
                parseInstant(startAt, "startAt", false),
                parseInstant(endAt, "endAt", true),
                null,
                page,
                size));
    }

    // ---------- NEWS-02 ----------

    /**
     * 资讯详情。
     *
     * <p>按 id 取详情**不要求它有关联**：一条没有任何确认关联的稿件仍然应该能被打开，
     * 否则列表里出现过的条目会点不进去（契约只要求"不返回未经授权的完整正文"）。
     *
     * <p>重复稿的 id 会被折叠到主记录（契约 §4.3：{@code newsId} "重复稿返回主记录 ID"）。
     * 列表从不返回重复稿 id，因此这条路径只在直接拼 URL 时走到，
     * 但它必须是可预测的，而不是"看运气返回哪一条"。
     */
    public NewsDetail detail(String newsId) {
        long id = parseNewsId(newsId);
        NewsRecord record = articles.find(id)
                .orElseThrow(() -> NewsNotFoundException.notFound(newsId));
        if (record.article().duplicate()) {
            long canonicalId = record.article().canonicalNewsId();
            record = articles.find(canonicalId)
                    .orElseThrow(() -> NewsNotFoundException.notFound(newsId));
        }
        NewsArticle article = record.article();
        if (article.contentStatus() == NewsContentStatus.WITHDRAWN) {
            throw NewsNotFoundException.withdrawn(newsId);
        }
        if (article.contentStatus() == NewsContentStatus.DELETED) {
            throw NewsNotFoundException.notFound(newsId);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        if (!record.source().usableOn(today) || expired(article, now)) {
            throw NewsNotFoundException.rightsExpired(newsId);
        }
        return toDetail(record);
    }

    // ---------- NEWS-03 ----------

    /** 公开的同步状态：只有聚合计数与时间，没有任何错误文本或来源名。 */
    public NewsSyncStatus syncStatus() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        int available = 0;
        int failed = 0;
        OffsetDateTime lastSuccess = null;
        for (NewsSource source : sources.findAll()) {
            if (source.usableOn(today)) {
                available++;
            }
            if (failed(source)) {
                failed++;
            }
            if (source.lastSuccessAt() != null
                    && (lastSuccess == null || source.lastSuccessAt().isAfter(lastSuccess))) {
                lastSuccess = source.lastSuccessAt();
            }
        }
        Long delaySeconds = lastSuccess == null
                ? null
                : Math.max(0, Duration.between(lastSuccess, now).getSeconds());
        NewsSyncStatus.OverallStatus overall;
        if (available == 0 || lastSuccess == null) {
            overall = NewsSyncStatus.OverallStatus.UNAVAILABLE;
        } else if (failed > 0) {
            overall = NewsSyncStatus.OverallStatus.DEGRADED;
        } else {
            overall = NewsSyncStatus.OverallStatus.OK;
        }
        return new NewsSyncStatus(overall, lastSuccess, delaySeconds, available, failed);
    }

    // ---------- NEWS-04 ----------

    /**
     * 受控筛选项。
     *
     * <p>可用时间范围取自**当前可见**的资讯：用"今天往前一年"填充会让前端的日期选择器
     * 给出一个看起来合法、实际没有任何数据的范围。
     */
    public NewsOptions options() {
        List<NewsRecord> visible = visible();
        OffsetDateTime earliest = null;
        OffsetDateTime latest = null;
        for (NewsRecord record : visible) {
            OffsetDateTime publishedAt = record.article().publishedAt();
            if (earliest == null || publishedAt.isBefore(earliest)) {
                earliest = publishedAt;
            }
            if (latest == null || publishedAt.isAfter(latest)) {
                latest = publishedAt;
            }
        }
        NewsTimeRange range = earliest == null
                ? NewsTimeRange.empty()
                : new NewsTimeRange(earliest, latest);
        return new NewsOptions(NewsType.codes(), NewsSourceType.codes(), range, FILTER_RULES);
    }

    // ---------- 自选域的 latestNewsCount ----------

    /**
     * {@inheritDoc}
     *
     * <p>只统计**已确认**的证券关联：一条被标为 CANDIDATE 的关联不该让自选卡片上出现
     * "有 1 条资讯"——那个数字会被读成"这只票有消息"，而低置信关联恰恰是"我们不确定"。
     */
    @Override
    public Map<String, Integer> countSince(Collection<String> securityIds, OffsetDateTime since) {
        if (securityIds == null || securityIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SecurityIdentity> identities = securityIdentities.resolveAll(securityIds);
        if (identities.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> outwardByStorageId = new LinkedHashMap<>();
        for (SecurityIdentity identity : identities.values()) {
            outwardByStorageId.put(identity.storageId(), identity.securityId());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NewsRecord record : visible()) {
            if (since != null && !record.article().publishedAt().isAfter(since)) {
                continue;
            }
            for (NewsRelation relation : record.confirmedRelations()) {
                if (relation.targetType() != NewsTargetType.SECURITY) {
                    continue;
                }
                String securityId = outwardByStorageId.get(relation.targetId());
                if (securityId != null) {
                    counts.merge(securityId, 1, Integer::sum);
                }
            }
        }
        return Map.copyOf(counts);
    }

    // ---------- AI 上下文证据（M3-06） ----------

    /**
     * {@inheritDoc}
     *
     * <p>它**复用** {@link #visible()} 这条可见性过滤链，只在其上再叠一层
     * {@code allow_ai_analysis}：资讯域已经知道"哪些资讯可见"，
     * AI 侧新增的知识只有一条——"来源是否允许进入 AI"。
     * 若在这里另写一遍可见性判据，两处口径迟早分叉，而分叉不会报错，
     * 只会让"列表里有 8 条、AI 说没有依据"同时成立。
     *
     * <p>{@code limit} 在**过滤之后**截断：先截断再过滤会让"最新 N 条里有若干条
     * 不允许进 AI"静默压低可用证据量——调用方以为自己拿到了 N 条候选，
     * 实际只有更少，而没有任何信号提示这件事。
     */
    @Override
    public List<NewsEvidence> evidenceFor(
            NewsTargetType targetType,
            String targetId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须为正数：" + limit);
        }
        TargetFilter target = resolveTarget(targetType, targetId);
        List<NewsRecord> matched = new ArrayList<>();
        for (NewsRecord record : visible()) {
            if (!record.source().allowAiAnalysis()) {
                continue;
            }
            if (!matchesRange(record, startAt, endAt)) {
                continue;
            }
            if (!matchesTarget(record, target)) {
                continue;
            }
            matched.add(record);
        }
        matched.sort(BY_PUBLISHED_DESC);
        List<NewsRecord> limited =
                matched.size() <= limit ? matched : matched.subList(0, limit);
        List<NewsEvidence> evidence = new ArrayList<>(limited.size());
        for (NewsRecord record : limited) {
            evidence.add(toEvidence(record));
        }
        return List.copyOf(evidence);
    }

    /** 稿件 → AI 证据的映射。只取模型与引用需要的最小字段集（架构 §11.4）。 */
    private static NewsEvidence toEvidence(NewsRecord record) {
        NewsArticle article = record.article();
        return new NewsEvidence(
                article.newsId(),
                article.newsType(),
                article.title(),
                article.summary(),
                record.source().sourceName(),
                article.publishedAt(),
                article.originalUrl(),
                article.originalAccessStatus());
    }

    // ---------- 共用过滤链 ----------

    private PageData<NewsSummary> page(NewsQuery query) {
        int effectivePage = validatePage(query.page());
        int effectiveSize = validatePageSize(query.size());
        // keyword 在这里归一（去空白、长度校验、空白→不过滤），而不是在匹配时逐条处理：
        // 两个入口（list(NewsQuery) 与 browse）因此共享同一份长度上限。少了这一步，
        // 上限形同虚设且不会有任何测试变红——超长关键词会被当成正常筛选条件照常执行。
        String keyword = validateKeyword(query.keyword());
        TargetFilter target = targetFilterOf(query);
        List<NewsRecord> filtered = visible().stream()
                .filter(record -> matchesTypes(record, query.newsTypes()))
                .filter(record -> matchesRange(record, query.startAt(), query.endAt()))
                .filter(record -> matchesKeyword(record, keyword))
                .filter(record -> matchesTarget(record, target))
                .sorted(BY_PUBLISHED_DESC)
                .toList();
        PageData<NewsRecord> slice = PageData.slice(filtered, effectivePage, effectiveSize);
        return new PageData<>(
                toSummaries(slice.items()),
                slice.page(),
                slice.size(),
                slice.total(),
                slice.totalPages(),
                slice.hasNext());
    }

    /**
     * 可见性过滤链。**所有**接口都经过这里，差异只在附加条件。
     *
     * <p>{@code now} 与 {@code today} 在一次调用里只取一次：跨零点时
     * "授权期判定用今天、内容过期判定用此刻"若各取一次时钟，同一次请求的两条判据
     * 会基于不同时刻，出现"列表可见但详情 404"的窗口。
     */
    private List<NewsRecord> visible() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        List<NewsRecord> visible = new ArrayList<>();
        for (NewsRecord record : articles.findAll()) {
            NewsArticle article = record.article();
            if (!article.publishedOriginal()) {
                continue;
            }
            if (!record.source().usableOn(today)) {
                continue;
            }
            if (expired(article, now)) {
                continue;
            }
            visible.add(record);
        }
        return visible;
    }

    private static boolean expired(NewsArticle article, OffsetDateTime now) {
        return article.rightsExpireAt() != null && !article.rightsExpireAt().isAfter(now);
    }

    private static boolean matchesTypes(NewsRecord record, Set<NewsType> newsTypes) {
        return newsTypes.isEmpty() || newsTypes.contains(record.article().newsType());
    }

    private static boolean matchesRange(
            NewsRecord record, OffsetDateTime startAt, OffsetDateTime endAt) {
        OffsetDateTime publishedAt = record.article().publishedAt();
        if (startAt != null && publishedAt.isBefore(startAt)) {
            return false;
        }
        return endAt == null || !publishedAt.isAfter(endAt);
    }

    private static boolean matchesKeyword(NewsRecord record, String keyword) {
        if (keyword == null) {
            return true;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        NewsArticle article = record.article();
        return contains(article.title(), needle) || contains(article.summary(), needle);
    }

    private static boolean contains(String haystack, String lowerCaseNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    private static boolean matchesTarget(NewsRecord record, TargetFilter target) {
        if (target == null) {
            return true;
        }
        return record.confirmedRelations().stream().anyMatch(relation ->
                relation.targetType() == target.type() && relation.targetId() == target.id());
    }

    /** 已解析的关联目标筛选条件。 */
    private record TargetFilter(NewsTargetType type, long id) {
    }

    /** 查询条件 → 目标筛选；三个筛选参数都为空时返回 {@code null} 表示"不按目标筛"。 */
    private TargetFilter targetFilterOf(NewsQuery query) {
        if (query.securityId() != null && !query.securityId().isBlank()) {
            return resolveTarget(NewsTargetType.SECURITY, query.securityId());
        }
        if (query.sectorId() != null && !query.sectorId().isBlank()) {
            return resolveTarget(NewsTargetType.SECTOR, query.sectorId());
        }
        if (query.marketCode() != null && !query.marketCode().isBlank()) {
            return resolveTarget(NewsTargetType.MARKET, query.marketCode());
        }
        return null;
    }

    /**
     * 把对外标识解析成关联表里的代理键。
     *
     * <p>解析不到一律**报错而不是返回空页**：路径里的证券/板块不存在与
     * "这只证券确实没有资讯"是两件事，用空页代替 404 会让"代码打错了"
     * 看起来像"这只票很安静"。
     *
     * <p>列表接口（NEWS-01 / STK-10 / SEC-07）与 AI 证据取数**共用**这一处：
     * 两处各写一遍解析，就会出现"列表按板块查得到、AI 按同一板块查不到"
     * 这类不会报错的分叉。
     */
    private TargetFilter resolveTarget(NewsTargetType targetType, String targetId) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType 不得为空");
        }
        return switch (targetType) {
            case SECURITY -> new TargetFilter(
                    NewsTargetType.SECURITY,
                    securityIdentities.resolve(targetId)
                            .orElseThrow(() -> NewsNotFoundException.securityNotFound(targetId))
                            .storageId());
            case SECTOR -> new TargetFilter(
                    NewsTargetType.SECTOR,
                    sectorIdentities.resolve(targetId)
                            .orElseThrow(() -> NewsNotFoundException.sectorNotFound(targetId))
                            .storageId());
            case MARKET -> new TargetFilter(
                    NewsTargetType.MARKET,
                    NewsMarketTargets.storageIdOf(targetId)
                            .orElseThrow(() -> new InvalidNewsQueryException(
                                    "不支持的市场代码：" + targetId)));
        };
    }

    // ---------- 映射 ----------

    private List<NewsSummary> toSummaries(List<NewsRecord> records) {
        Map<Long, SecurityIdentity> securities = resolveSecurities(records);
        Map<Long, SectorIdentity> sectors = resolveSectors(records);
        List<NewsSummary> summaries = new ArrayList<>(records.size());
        for (NewsRecord record : records) {
            NewsArticle article = record.article();
            summaries.add(new NewsSummary(
                    Long.toString(article.newsId()),
                    article.newsType(),
                    article.title(),
                    article.summary(),
                    record.source().sourceName(),
                    article.authorName(),
                    article.publishedAt(),
                    article.collectedAt(),
                    article.originalUrl(),
                    article.originalAccessStatus(),
                    toRelationSummaries(record, securities, sectors)));
        }
        return List.copyOf(summaries);
    }

    private NewsDetail toDetail(NewsRecord record) {
        NewsArticle article = record.article();
        List<NewsRecord> single = List.of(record);
        Map<Long, SecurityIdentity> securities = resolveSecurities(single);
        Map<Long, SectorIdentity> sectors = resolveSectors(single);
        return new NewsDetail(
                Long.toString(article.newsId()),
                article.newsType(),
                article.title(),
                article.summary(),
                record.source().sourceName(),
                record.source().sourceType(),
                article.authorName(),
                article.publishedAt(),
                article.collectedAt(),
                article.originalUrl(),
                article.originalAccessStatus(),
                article.languageCode(),
                article.rightsExpireAt(),
                COPYRIGHT_NOTICE_TEMPLATE.formatted(record.source().sourceName()),
                toRelationSummaries(record, securities, sectors));
    }

    /**
     * 关联摘要的对外映射。
     *
     * <p>{@code targetId} 必须是**对外标识**（{@code sim-600519} / {@code sim-bk0001} / {@code CN}）：
     * 返回库里的 bigint 会让前端拼出的跳转链接 404（M2-06 / M2-11 已各踩过一次）。
     * 代理键 → 对外标识的桥接只走 {@code *IdentityProvider}，不在这里拼字符串。
     */
    private static List<NewsRelationSummary> toRelationSummaries(
            NewsRecord record,
            Map<Long, SecurityIdentity> securities,
            Map<Long, SectorIdentity> sectors) {
        List<NewsRelationSummary> summaries = new ArrayList<>();
        for (NewsRelation relation : record.confirmedRelations()) {
            switch (relation.targetType()) {
                case SECURITY -> {
                    SecurityIdentity identity = securities.get(relation.targetId());
                    if (identity != null) {
                        summaries.add(new NewsRelationSummary(
                                NewsTargetType.SECURITY,
                                identity.securityId(),
                                identity.summary().securityCode(),
                                identity.summary().securityName(),
                                relation.relationMethod(),
                                relation.confidenceScore()));
                    }
                }
                case SECTOR -> {
                    SectorIdentity identity = sectors.get(relation.targetId());
                    if (identity != null) {
                        summaries.add(new NewsRelationSummary(
                                NewsTargetType.SECTOR,
                                identity.sectorId(),
                                identity.sector().sectorCode(),
                                identity.sector().sectorName(),
                                relation.relationMethod(),
                                relation.confidenceScore()));
                    }
                }
                case MARKET -> NewsMarketTargets.marketCodeOf(relation.targetId())
                        .ifPresent(marketCode -> summaries.add(new NewsRelationSummary(
                                NewsTargetType.MARKET,
                                marketCode,
                                marketCode,
                                marketCode,
                                relation.relationMethod(),
                                relation.confidenceScore())));
            }
        }
        return List.copyOf(summaries);
    }

    private Map<Long, SecurityIdentity> resolveSecurities(List<NewsRecord> records) {
        Set<Long> storageIds = new LinkedHashSet<>();
        for (NewsRecord record : records) {
            for (NewsRelation relation : record.confirmedRelations()) {
                if (relation.targetType() == NewsTargetType.SECURITY) {
                    storageIds.add(relation.targetId());
                }
            }
        }
        return storageIds.isEmpty() ? Map.of() : securityIdentities.findByStorageIds(storageIds);
    }

    private Map<Long, SectorIdentity> resolveSectors(List<NewsRecord> records) {
        Set<Long> storageIds = new LinkedHashSet<>();
        for (NewsRecord record : records) {
            for (NewsRelation relation : record.confirmedRelations()) {
                if (relation.targetType() == NewsTargetType.SECTOR) {
                    storageIds.add(relation.targetId());
                }
            }
        }
        return storageIds.isEmpty() ? Map.of() : sectorIdentities.findByStorageIds(storageIds);
    }

    /** 排序口径：{@code published_at DESC, news_id DESC}（同秒时由 id 决定稳定顺序）。 */
    private static final Comparator<NewsRecord> BY_PUBLISHED_DESC =
            Comparator.comparing((NewsRecord record) -> record.article().publishedAt())
                    .reversed()
                    .thenComparing(Comparator.comparingLong(
                            (NewsRecord record) -> record.article().newsId()).reversed());

    // ---------- 参数校验 ----------

    private static long parseNewsId(String newsId) {
        if (newsId == null || newsId.isBlank()) {
            throw NewsNotFoundException.notFound(newsId);
        }
        try {
            return Long.parseLong(newsId.trim());
        } catch (NumberFormatException exception) {
            // 路径上的 id 不认识就是"找不到"，不是"参数格式错误"——400 会暗示"改一下格式就能找到"。
            throw NewsNotFoundException.notFound(newsId);
        }
    }

    /**
     * 解析资讯类型参数，支持逗号分隔的多值（NEWS-01 的 {@code newsTypes}）。
     *
     * <p>未知取值**报错而不是静默忽略**：忽略会让"NEWSX"这类拼写错误变成
     * "返回了全部资讯"，用户看到的是"筛选没生效"，而真正的原因是拼错了。
     */
    private static Set<NewsType> parseNewsTypes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<NewsType> types = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            types.add(NewsType.fromCode(trimmed).orElseThrow(() -> new InvalidNewsQueryException(
                    "不支持的资讯类型：" + trimmed + "，可选值 " + NewsType.codes())));
        }
        return types;
    }

    /**
     * 解析时间参数。接受两种写法：
     *
     * <ul>
     *   <li>完整 ISO-8601 偏移时间（{@code 2026-09-18T10:00:00+08:00}）；
     *   <li>纯日期（{@code 2026-09-18}）——作为区间端点时取当日的起点或终点，
     *       否则 {@code endAt=2026-09-18} 会把当天 10:00 发布的资讯排除在外。
     * </ul>
     */
    private OffsetDateTime parseInstant(String value, String field, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == 10) {
                LocalDate date = LocalDate.parse(text);
                return (endOfDay
                        ? date.plusDays(1).atStartOfDay()
                        : date.atStartOfDay())
                        .atZone(clock.getZone())
                        .toOffsetDateTime();
            }
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            throw new InvalidNewsQueryException(
                    field + " 必须是 ISO-8601 时间（如 2026-09-18T10:00:00+08:00）或日期（如 2026-09-18）");
        }
    }

    private static int validatePage(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < 1) {
            throw new InvalidNewsQueryException("page 必须大于等于 1");
        }
        return page;
    }

    private static int validatePageSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidNewsQueryException("size 必须为 1 至 " + MAX_PAGE_SIZE);
        }
        return size;
    }

    /** 空串与全空白视为"不筛选"，而不是"筛选一个空白关键词"。 */
    private static String validateKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new InvalidNewsQueryException(
                    "keyword 长度不得超过 " + MAX_KEYWORD_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static boolean failed(NewsSource source) {
        if (source.status() == NewsSource.SourceStatus.DEGRADED) {
            return true;
        }
        if (source.lastFailureAt() == null) {
            return false;
        }
        return source.lastSuccessAt() == null
                || source.lastFailureAt().isAfter(source.lastSuccessAt());
    }
}
