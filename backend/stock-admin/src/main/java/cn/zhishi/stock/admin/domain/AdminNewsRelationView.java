package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 资讯关联的对外视图（契约 §17.2 ADM-NEWS-05 的返回形状）。
 *
 * <p>与 {@link AdminNewsRelationEntry} 的唯一区别是目标三件套换成了对外标识：
 * {@code targetId} 是 {@code sim-600519} / {@code sim-bk0001} / {@code CN}，
 * {@code targetCode} / {@code targetName} 是目标摘要（证券代码与名称、板块代码与名称，
 * 或市场代码本身）。目标无法解析（如行情主数据里已不存在）时三者都是
 * {@code null} 而不是编造值——"这个目标现在解析不出来"本身就是运维要看的健康信号。
 */
public record AdminNewsRelationView(
        long relationId,
        long newsId,
        String newsTitle,
        NewsTargetType targetType,
        String targetId,
        String targetCode,
        String targetName,
        NewsRelationMethod relationMethod,
        BigDecimal confidenceScore,
        NewsRelationStatus relationStatus,
        String reasonSummary,
        Long reviewedBy,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt) {

    public static AdminNewsRelationView of(AdminNewsRelationEntry entry) {
        return new AdminNewsRelationView(
                entry.relationId(), entry.newsId(), entry.newsTitle(),
                entry.targetType(), null, null, null,
                entry.relationMethod(), entry.confidenceScore(), entry.relationStatus(),
                entry.reasonSummary(), entry.reviewedBy(), entry.reviewedAt(),
                entry.createdAt());
    }

    public AdminNewsRelationView withTarget(String targetId, String targetCode, String targetName) {
        return new AdminNewsRelationView(
                relationId, newsId, newsTitle, targetType,
                targetId, targetCode, targetName,
                relationMethod, confidenceScore, relationStatus,
                reasonSummary, reviewedBy, reviewedAt, createdAt);
    }
}
