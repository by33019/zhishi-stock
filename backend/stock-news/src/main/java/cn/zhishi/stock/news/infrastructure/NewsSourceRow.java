package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code news_source} 的行映射结果，与 {@link NewsSource} 字段一一对应。
 *
 * <p>时间戳用 {@link LocalDateTime} 而不是 {@code OffsetDateTime}：
 * MySQL 的 {@code datetime(3)} 不带时区，JDBC 驱动按**连接时区**解释它。
 * 与 {@code WatchlistItemRow} 同一处理方式——由仓储用同一个 {@code Clock} 的时区
 * 写入与回读，墙上时间因此无损往返；直接映射成 {@code OffsetDateTime}
 * 会把结果交给驱动的时区推断，本地与 CI（UTC）可能给出不同时刻。
 */
public record NewsSourceRow(
        long sourceId,
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
        int version) {
}
