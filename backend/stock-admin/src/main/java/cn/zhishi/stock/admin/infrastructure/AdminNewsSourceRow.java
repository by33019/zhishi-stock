package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code news_source} 的一行。
 *
 * <p>枚举列直接用资讯域的类型承载（MyBatis 默认按 {@code name()} 映射），
 * 与资讯域自己的 {@code NewsSourceRow} 同一做法：CHECK 约束保证列里只会出现
 * 枚举名，专门写一遍字符串 ↔ 枚举转换只是重复表意。
 *
 * <p>不出 {@code infrastructure} 包。
 */
public record AdminNewsSourceRow(
        long sourceId,
        Long providerId,
        String sourceCode,
        String sourceName,
        NewsSourceType sourceType,
        String homepageUrl,
        NewsSource.AuthorizationStatus authorizationStatus,
        LocalDate rightsValidFrom,
        LocalDate rightsValidTo,
        boolean allowAiAnalysis,
        NewsSource.SourceStatus status,
        LocalDateTime lastSuccessAt,
        LocalDateTime lastFailureAt,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
