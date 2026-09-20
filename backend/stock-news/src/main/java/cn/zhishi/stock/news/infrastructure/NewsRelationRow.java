package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.math.BigDecimal;

/**
 * {@code stock_news_relation} 的行映射结果。
 *
 * <p>{@code confidenceScore} 用 {@link BigDecimal} 而不是 {@code double}：
 * 列是 {@code decimal(6,5)}，用 {@code double} 会让 {@code 0.80000} 与 {@code 0.8}
 * 在比较时"看起来相等"却有不同的字符串形式，而契约把这个值直接返回给前端。
 */
public record NewsRelationRow(
        long relationId,
        long newsId,
        NewsTargetType targetType,
        long targetId,
        NewsRelationMethod relationMethod,
        BigDecimal confidenceScore,
        NewsRelationStatus relationStatus,
        String reasonSummary) {
}
