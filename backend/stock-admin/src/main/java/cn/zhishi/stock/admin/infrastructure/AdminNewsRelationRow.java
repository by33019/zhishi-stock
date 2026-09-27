package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code stock_news_relation} 的一行（列表、详情、复核共用）。
 *
 * <p>{@code newsTitle} 来自与 {@code stock_news} 的 JOIN（契约 ADM-NEWS-05 的
 * "新闻摘要"），不是关联表自己的列。reviewed 两列是资讯域行对象没有的后台字段，
 * 见 {@code AdminNewsRelationEntry} 的说明。不出 {@code infrastructure} 包。
 */
public record AdminNewsRelationRow(
        long relationId,
        long newsId,
        String newsTitle,
        NewsTargetType targetType,
        long targetId,
        NewsRelationMethod relationMethod,
        BigDecimal confidenceScore,
        NewsRelationStatus relationStatus,
        String reasonSummary,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {
}
