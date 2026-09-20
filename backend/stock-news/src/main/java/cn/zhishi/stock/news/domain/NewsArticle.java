package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;

/**
 * 一条稿件，字段与 {@code stock_news} 对齐。
 *
 * <p>{@code sourceId} 而不是 {@code source} 对象：稿件表存的是来源 ID，
 * 把来源嵌进来会让"来源被停用"这件事无法在读取时反映（稿件里的来源是采集当时的快照）。
 * 需要来源信息时由 {@link NewsRecord} 组合，保证读到的永远是**当前**的来源状态。
 *
 * @param canonicalNewsId 仅当 {@link #dedupStatus()} 为 {@code DUPLICATE} 时非空，
 *                        指向跨来源重复对里的主记录；不变量由 {@link NewsDeduplicator} 唯一实现
 * @param rightsExpireAt  单条内容的授权失效时间；与来源级授权是两个层级，都要判
 */
public record NewsArticle(
        long newsId,
        long sourceId,
        String sourceContentId,
        NewsType newsType,
        String title,
        String summary,
        String authorName,
        String originalUrl,
        String languageCode,
        OffsetDateTime publishedAt,
        OffsetDateTime collectedAt,
        String contentFingerprint,
        Long canonicalNewsId,
        NewsDedupStatus dedupStatus,
        NewsContentStatus contentStatus,
        NewsOriginalAccessStatus originalAccessStatus,
        OffsetDateTime rightsExpireAt) {

    /** 是否为跨来源重复稿（会被折叠到主记录，不进任何前台列表）。 */
    public boolean duplicate() {
        return dedupStatus == NewsDedupStatus.DUPLICATE;
    }

    /** 是否为已发布的主记录——列表可见性的第一道判据。 */
    public boolean publishedOriginal() {
        return contentStatus == NewsContentStatus.PUBLISHED
                && dedupStatus == NewsDedupStatus.ORIGINAL;
    }
}
