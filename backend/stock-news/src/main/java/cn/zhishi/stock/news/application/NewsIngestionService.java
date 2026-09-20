package cn.zhishi.stock.news.application;

import cn.zhishi.stock.news.domain.NewsArticle;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsContentStatus;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsDeduplicator;
import cn.zhishi.stock.news.domain.NewsFeed;
import cn.zhishi.stock.news.domain.NewsFeedItem;
import cn.zhishi.stock.news.domain.NewsFingerprint;
import cn.zhishi.stock.news.domain.NewsIngestionResult;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsProvider;
import cn.zhishi.stock.news.domain.NewsRelation;
import cn.zhishi.stock.news.domain.NewsRelationResolver;
import cn.zhishi.stock.news.domain.NewsRelationStore;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.domain.NewsUrlPolicy;
import cn.zhishi.stock.news.domain.RelationCatalogProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集：取数 → 登记来源 → 授权闸门 → 来源 ID 幂等 → 内容指纹去重 → 落库 → 仅主记录解析关联。
 *
 * <h2>两条幂等路径的顺序是刻意的</h2>
 * "来源 ID 幂等"必须先于"内容指纹去重"：同一份稿件被重复投递（采集重试、游标回退）时，
 * 若先算指纹，一条内容已被来源更新的重投会被判成新的 ORIGINAL 而不是跳过——
 * 于是同一来源的同一稿件在库里出现两次，而 {@code uk_stock_news_source_content}
 * 会把它拒绝掉。先查来源 ID，这次投递连指纹都不用算。
 *
 * <h2>为什么只有 DuplicateKeyException 被吞掉</h2>
 * 它是**正常**的并发结果：另一个采集进程在我们查完 {@code existsBySourceContent}
 * 之后、插入之前先写了同一条。其余任何异常一律向上抛，整个批次回滚——
 * 批次是幂等的（重复投递会被上面两条判据挡住），下一次采集重试是安全的，
 * 而逐条吞异常会留下"一部分稿件进了库、它们的关联没进"的静默半成品。
 *
 * <h2>失败留痕必须独立事务</h2>
 * {@link #recordSyncFailure} 标 {@code REQUIRES_NEW}：它由采集调度方在
 * {@link #ingest} 抛出之后调用，而那时外层事务正在回滚——同一个事务里写的失败标记
 * 会跟着一起消失，症状是"每次失败都不留痕迹"。
 */
public class NewsIngestionService {

    private static final String MARKET_CODE = "CN";

    private final NewsProvider provider;
    private final NewsSourceStore sources;
    private final NewsArticleStore articles;
    private final NewsRelationStore relations;
    private final RelationCatalogProvider catalogs;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public NewsIngestionService(
            NewsProvider provider,
            NewsSourceStore sources,
            NewsArticleStore articles,
            NewsRelationStore relations,
            RelationCatalogProvider catalogs,
            LongSupplier idGenerator,
            Clock clock) {
        this.provider = provider;
        this.sources = sources;
        this.articles = articles;
        this.relations = relations;
        this.catalogs = catalogs;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** 执行一次采集。{@code since} 为 {@code null} 表示不设下界。 */
    @Transactional
    public NewsIngestionResult ingest(OffsetDateTime since) {
        // 来源登记：模拟实现自带清单，真实环境下由后台登记（ADM-NEWS-03）。
        // 只插入缺失，不覆盖已有授权状态——Provider 无权改人工决定。
        sources.ensureAll(provider.sources());

        Map<String, NewsSource> byCode = new LinkedHashMap<>();
        for (NewsSource source : sources.findAll()) {
            byCode.put(source.sourceCode(), source);
        }
        LocalDate today = LocalDate.now(clock);
        NewsFeed feed = provider.fetch(since);
        NewsRelationResolver resolver = new NewsRelationResolver(catalogs.load());

        int inserted = 0;
        int deduplicated = 0;
        int skipped = 0;
        int relationCount = 0;
        Set<String> skipReasons = new LinkedHashSet<>();

        for (NewsFeedItem item : feed.items()) {
            NewsSource source = byCode.get(item.sourceCode());
            if (source == null) {
                skipped++;
                skipReasons.add("来源未登记：" + item.sourceCode());
                continue;
            }
            if (!source.usableOn(today)) {
                skipped++;
                skipReasons.add("来源不可用（未授权或已停用）：" + source.sourceCode());
                continue;
            }
            if (!NewsUrlPolicy.isAllowed(item.originalUrl())) {
                skipped++;
                skipReasons.add("原文地址不在协议白名单内：" + source.sourceCode());
                continue;
            }
            if (articles.existsBySourceContent(source.sourceId(), item.sourceContentId())) {
                skipped++;
                skipReasons.add("来源 ID 幂等命中，跳过重复投递");
                continue;
            }

            String fingerprint = NewsFingerprint.of(item.title(), item.summary());
            NewsDeduplicator.Decision decision = NewsDeduplicator.decide(
                    fingerprint,
                    articles.findOriginalNewsIdByFingerprint(fingerprint).orElse(null));
            long newsId = idGenerator.getAsLong();
            NewsArticle article = new NewsArticle(
                    newsId,
                    source.sourceId(),
                    item.sourceContentId(),
                    item.newsType(),
                    item.title(),
                    item.summary(),
                    item.authorName(),
                    item.originalUrl(),
                    item.languageCode(),
                    item.publishedAt(),
                    OffsetDateTime.now(clock),
                    fingerprint,
                    decision.canonicalNewsId(),
                    decision.status(),
                    NewsContentStatus.PUBLISHED,
                    // 未校验过原文可访问性：UNKNOWN 而不是 AVAILABLE（后者是编造）。
                    NewsOriginalAccessStatus.UNKNOWN,
                    null);
            try {
                articles.insert(article);
            } catch (DuplicateKeyException exception) {
                skipped++;
                skipReasons.add("并发下同来源同稿件已被写入，跳过");
                continue;
            }
            inserted++;
            if (decision.status() == NewsDedupStatus.DUPLICATE) {
                deduplicated++;
                // 重复稿不解析关联：它会被折叠到主记录，给重复稿也挂关联
                // 会让"某公司最近资讯"出现两条内容完全相同的条目。
                continue;
            }
            List<NewsRelation> rows = toRelations(newsId, resolver.resolve(item));
            if (!rows.isEmpty()) {
                relations.insertAll(rows);
                relationCount += rows.size();
            }
        }

        sources.recordSyncSuccess(touchedSourceIds(byCode, feed, today), feed.fetchedAt());
        return new NewsIngestionResult(
                feed.items().size(),
                inserted,
                deduplicated,
                skipped,
                relationCount,
                List.copyOf(skipReasons));
    }

    /**
     * 记录"本次采集整体失败"。
     *
     * <p>必须在独立事务里执行（见类注释），因此由调度方在 {@link #ingest} 抛出之后调用。
     * 失败的判据是"来源在本次尝试中没拿到任何数据"，所以标记范围是**全部可用来源**——
     * 取数整体失败时无法知道是哪一家的问题。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSyncFailure(OffsetDateTime at) {
        LocalDate today = LocalDate.now(clock);
        List<Long> usable = new ArrayList<>();
        for (NewsSource source : sources.findAll()) {
            if (source.usableOn(today)) {
                usable.add(source.sourceId());
            }
        }
        if (!usable.isEmpty()) {
            sources.recordSyncFailure(usable, at);
        }
    }

    /**
     * 本次采集"覆盖到且可用"的来源：Provider 声明的 + 批次里实际出现的。
     *
     * <p>不用"全部已登记来源"：Provider 可能只覆盖其中几家，
     * 把没碰过的来源标成"同步成功"是编造一次不存在的采集。
     */
    private List<Long> touchedSourceIds(
            Map<String, NewsSource> byCode, NewsFeed feed, LocalDate today) {
        Set<String> codes = new LinkedHashSet<>();
        for (NewsSource declared : provider.sources()) {
            codes.add(declared.sourceCode());
        }
        for (NewsFeedItem item : feed.items()) {
            codes.add(item.sourceCode());
        }
        List<Long> ids = new ArrayList<>();
        for (String code : codes) {
            NewsSource source = byCode.get(code);
            if (source != null && source.usableOn(today)) {
                ids.add(source.sourceId());
            }
        }
        return List.copyOf(ids);
    }

    private List<NewsRelation> toRelations(
            long newsId, List<NewsRelationResolver.ResolvedRelation> resolved) {
        List<NewsRelation> rows = new ArrayList<>(resolved.size());
        for (NewsRelationResolver.ResolvedRelation relation : resolved) {
            rows.add(new NewsRelation(
                    idGenerator.getAsLong(),
                    newsId,
                    relation.targetType(),
                    relation.targetId(),
                    relation.relationMethod(),
                    relation.confidenceScore(),
                    relation.relationStatus(),
                    relation.reasonSummary()));
        }
        return List.copyOf(rows);
    }
}
