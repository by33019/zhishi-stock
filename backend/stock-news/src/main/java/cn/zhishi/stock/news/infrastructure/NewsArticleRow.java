package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsContentStatus;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsType;
import java.time.LocalDateTime;

/**
 * {@code stock_news} 的行映射结果，与 {@link cn.zhishi.stock.news.domain.NewsArticle} 一一对应。
 *
 * <p>列名与字段名不同的一处是 {@code authorized_summary → summary}：
 * 库里刻意叫 {@code authorized_summary}，提醒写入方"这里只能放授权范围内可展示的摘要"，
 * 不是原文正文。别名在 SQL 里显式给出，不让隐式驼峰规则去猜。
 */
public record NewsArticleRow(
        long newsId,
        long sourceId,
        String sourceContentId,
        NewsType newsType,
        String title,
        String summary,
        String authorName,
        String originalUrl,
        String languageCode,
        LocalDateTime publishedAt,
        LocalDateTime collectedAt,
        String contentFingerprint,
        Long canonicalNewsId,
        NewsDedupStatus dedupStatus,
        NewsContentStatus contentStatus,
        NewsOriginalAccessStatus originalAccessStatus,
        LocalDateTime rightsExpireAt) {
}
