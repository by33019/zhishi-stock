package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 资讯关联的后台条目（契约 §17.2 ADM-NEWS-05/06/08）。
 *
 * <h2>{@code targetId} 是库里的代理键，出网前必须换掉</h2>
 * {@code stock_news_relation.target_id} 是 bigint 代理键，而契约与前端全程用
 * 对外标识（{@code sim-600519} / {@code sim-bk0001} / {@code CN}）。本记录是
 * 仓储层的原始形状；对外标识与目标摘要由用例层经身份端口解析后填进
 * {@link AdminNewsRelationView} 再返回——直接把代理键发给前端，拼出的跳转
 * 链接就是 404（M2-06 / M2-11 各踩过一次）。
 *
 * <h2>reviewedBy / reviewedAt 是后台侧的字段</h2>
 * V4 的表里有这两列，但资讯域的 {@code NewsRelation} 没带（采集侧不读不写它们）。
 * 复核是后台的用例，字段由本记录承载；{@code reviewedBy} 是 {@code sys_user.id}。
 */
public record AdminNewsRelationEntry(
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
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt) {
}
