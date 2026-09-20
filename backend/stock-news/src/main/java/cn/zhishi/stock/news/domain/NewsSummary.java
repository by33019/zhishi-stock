package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资讯摘要，字段与 {@code RESTful-API.md} §4.3 逐一对齐。
 *
 * <p>{@code summary} 来自 {@code stock_news.authorized_summary}：
 * **授权范围内可展示的摘要**，不是原文正文。契约 §11.2 明确"不返回未经授权的完整正文"。
 *
 * <p>{@code relations} 只含 {@code relationStatus = CONFIRMED} 的关联
 * （契约 §11.2：低置信候选关联不进入普通列表与 AI 证据）。
 */
public record NewsSummary(
        String newsId,
        NewsType newsType,
        String title,
        String summary,
        String sourceName,
        String authorName,
        OffsetDateTime publishedAt,
        OffsetDateTime collectedAt,
        String originalUrl,
        NewsOriginalAccessStatus originalAccessStatus,
        List<NewsRelationSummary> relations) {

    public NewsSummary {
        relations = List.copyOf(relations);
    }
}
