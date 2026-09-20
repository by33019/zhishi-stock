package cn.zhishi.stock.news.domain;

import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorType;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资讯域测试的极小手写夹具。
 *
 * <p>刻意**不**复用模拟 Provider：模拟源的名称与编号会随算法漂移，
 * 断言里一旦出现它们，测试就从"验证规则"变成"记录当前输出"。
 * 每个域各放一份自己的桩是刻意的，不要"优化"掉（同 M2 各模块的做法）。
 *
 * <p>跨包可见：{@code domain} 与 {@code application} 的测试共用同一份夹具，
 * 免得"同一只证券"在两个测试包里被构造成不同的代理键——那种不一致会让
 * 一条本该失败的断言悄悄通过。
 */
public final class NewsFixtures {

    public static final OffsetDateTime PUBLISHED = OffsetDateTime.parse("2026-09-18T10:00:00+08:00");

    public static final String SOURCE_A = "SIM_MEDIA_A";
    public static final String SOURCE_B = "SIM_MEDIA_B";
    public static final String SOURCE_SUSPENDED = "SIM_MEDIA_SUSPENDED";

    private NewsFixtures() {
    }

    // ---------- 标的 ----------

    /** 一只证券；{@code code} 同时充当代理键（与模拟实现的取舍一致）。 */
    public static SecurityIdentity security(String code, String name) {
        return new SecurityIdentity(
                Long.parseLong(code),
                new SecuritySummary(
                        "sim-" + code,
                        "SH" + code,
                        code,
                        name,
                        "SH",
                        "STOCK",
                        "MAIN",
                        "LISTED",
                        false,
                        false,
                        2,
                        null,
                        null));
    }

    /** 一个板块；{@code ordinal} 同时充当代理键（与模拟实现的取舍一致）。 */
    public static SectorIdentity sector(int ordinal, String name) {
        return new SectorIdentity(
                ordinal,
                new Sector(
                        "sim-bk" + "0".repeat(Math.max(0, 4 - Integer.toString(ordinal).length())) + ordinal,
                        "BK" + ordinal,
                        name,
                        SectorType.INDUSTRY.code(),
                        null,
                        1,
                        Sector.STATUS_ACTIVE));
    }

    // ---------- 来源 ----------

    public static NewsSource source(long id, String code) {
        return source(id, code, NewsSource.AuthorizationStatus.AUTHORIZED, NewsSource.SourceStatus.ACTIVE);
    }

    public static NewsSource source(
            long id,
            String code,
            NewsSource.AuthorizationStatus authorization,
            NewsSource.SourceStatus status) {
        return new NewsSource(
                id,
                code,
                "来源" + code,
                NewsSourceType.MEDIA,
                "https://example.com/" + code.toLowerCase(java.util.Locale.ROOT),
                authorization,
                null,
                null,
                true,
                status,
                null,
                null,
                0);
    }

    public static NewsSource source(
            long id,
            String code,
            NewsSource.AuthorizationStatus authorization,
            NewsSource.SourceStatus status,
            OffsetDateTime lastSuccessAt,
            OffsetDateTime lastFailureAt) {
        NewsSource base = source(id, code, authorization, status);
        return new NewsSource(
                base.sourceId(),
                base.sourceCode(),
                base.sourceName(),
                base.sourceType(),
                base.homepageUrl(),
                base.authorizationStatus(),
                base.rightsValidFrom(),
                base.rightsValidTo(),
                base.allowAiAnalysis(),
                base.status(),
                lastSuccessAt,
                lastFailureAt,
                base.version());
    }

    /**
     * 授权有效但**不允许进入 AI 上下文**的来源（{@code allow_ai_analysis = 0}）。
     *
     * <p>它是 M3-06 的 `allow_ai_analysis` 消费侧的判据来源：资讯列表里能看到的稿件，
     * 若来自这个来源，就**不能**成为 AI 证据。
     */
    public static NewsSource sourceWithoutAi(long id, String code) {
        NewsSource base = source(id, code);
        return new NewsSource(
                base.sourceId(),
                base.sourceCode(),
                base.sourceName(),
                base.sourceType(),
                base.homepageUrl(),
                base.authorizationStatus(),
                base.rightsValidFrom(),
                base.rightsValidTo(),
                false,
                base.status(),
                base.lastSuccessAt(),
                base.lastFailureAt(),
                base.version());
    }

    /** 授权期已过的来源。 */
    public static NewsSource expiredRights(long id, String code, java.time.LocalDate validTo) {
        NewsSource base = source(id, code);
        return new NewsSource(
                base.sourceId(),
                base.sourceCode(),
                base.sourceName(),
                base.sourceType(),
                base.homepageUrl(),
                base.authorizationStatus(),
                base.rightsValidFrom(),
                validTo,
                base.allowAiAnalysis(),
                base.status(),
                null,
                null,
                0);
    }

    // ---------- 稿件与关联 ----------

    public static NewsArticle article(long newsId, long sourceId, String title, String summary) {
        return article(
                newsId, sourceId, title, summary, NewsDedupStatus.ORIGINAL, null,
                NewsContentStatus.PUBLISHED, null);
    }

    public static NewsArticle article(
            long newsId,
            long sourceId,
            String title,
            String summary,
            NewsDedupStatus dedupStatus,
            Long canonicalNewsId,
            NewsContentStatus contentStatus,
            OffsetDateTime rightsExpireAt) {
        return new NewsArticle(
                newsId,
                sourceId,
                "content-" + newsId,
                NewsType.NEWS,
                title,
                summary,
                "记者",
                "https://example.com/news/" + newsId,
                "zh-CN",
                PUBLISHED,
                PUBLISHED,
                NewsFingerprint.of(title, summary),
                canonicalNewsId,
                dedupStatus,
                contentStatus,
                NewsOriginalAccessStatus.UNKNOWN,
                rightsExpireAt);
    }

    /**
     * 指定**发布时间**与内容授权失效时间的稿件。
     *
     * <p>上面那个 8 参重载的第 8 个参数是 {@code rightsExpireAt}，发布时间恒为 {@link #PUBLISHED}。
     * 需要按发布时间做区间/排序断言时用它——把发布时间传给 {@code rightsExpireAt}
     * 会让稿件直接"授权过期"而不可见，而断言失败的信息只会说"列表是空的"，
     * 看起来像过滤链坏了，实际是夹具参数传错了位置。
     */
    public static NewsArticle article(
            long newsId,
            long sourceId,
            String title,
            String summary,
            NewsDedupStatus dedupStatus,
            Long canonicalNewsId,
            NewsContentStatus contentStatus,
            OffsetDateTime publishedAt,
            OffsetDateTime rightsExpireAt) {
        NewsArticle base = article(
                newsId, sourceId, title, summary, dedupStatus, canonicalNewsId, contentStatus, null);
        return new NewsArticle(
                base.newsId(),
                base.sourceId(),
                base.sourceContentId(),
                base.newsType(),
                base.title(),
                base.summary(),
                base.authorName(),
                base.originalUrl(),
                base.languageCode(),
                publishedAt,
                base.collectedAt(),
                base.contentFingerprint(),
                base.canonicalNewsId(),
                base.dedupStatus(),
                base.contentStatus(),
                base.originalAccessStatus(),
                rightsExpireAt);
    }

    public static NewsRelation relation(
            long newsId, NewsTargetType type, long targetId, NewsRelationStatus status) {
        BigDecimal confidence = status == NewsRelationStatus.CONFIRMED
                ? new BigDecimal("0.80000")
                : new BigDecimal("0.60000");
        return new NewsRelation(
                1_000L + targetId,
                newsId,
                type,
                targetId,
                NewsRelationMethod.RULE,
                confidence,
                status,
                "测试夹具");
    }

    public static NewsRecord record(
            NewsArticle article, NewsSource source, List<NewsRelation> relations) {
        return new NewsRecord(article, source, relations);
    }

    // ---------- 采集条目 ----------

    /** 一条最小可用的来源条目：只有标题与摘要参与文本匹配。 */
    public static NewsFeedItem item(String title, String summary) {
        return item(SOURCE_A, "content-1", title, summary, List.of(), List.of(), null);
    }

    public static NewsFeedItem item(
            String title,
            String summary,
            List<String> securityCodes,
            List<String> sectorCodes,
            String marketCode) {
        return item(SOURCE_A, "content-1", title, summary, securityCodes, sectorCodes, marketCode);
    }

    public static NewsFeedItem item(
            String sourceCode,
            String sourceContentId,
            String title,
            String summary,
            List<String> securityCodes,
            List<String> sectorCodes,
            String marketCode) {
        return new NewsFeedItem(
                sourceCode,
                sourceContentId,
                NewsType.NEWS,
                title,
                summary,
                "编辑",
                "https://example.com/news/" + sourceContentId,
                "zh-CN",
                PUBLISHED,
                securityCodes,
                sectorCodes,
                marketCode);
    }
}
