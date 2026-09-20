package cn.zhishi.stock.news.domain;

import java.math.BigDecimal;

/**
 * 资讯与标的（证券 / 板块 / 市场）的关联，字段与 {@code stock_news_relation} 对齐。
 *
 * <p>与 {@code uk_news_relation_target(news_id, target_type, target_id)} 配套的不变量：
 * **同一 (资讯, 目标类型, 目标) 只有一条关联**。解析器必须在内存里去重（取置信度最高者），
 * 而不是靠撞唯一索引——撞索引失败时"哪条留下"取决于插入顺序，那是"看起来对、其实不确定"。
 *
 * @param confidenceScore {@code 0} 到 {@code 1}；显式关联可为空（来源说了就算，
 *                        不存在"置信度多少"这个问题）
 * @param reasonSummary   关联依据摘要，供后台复核时回答"为什么把它和这只证券关联起来"
 */
public record NewsRelation(
        long relationId,
        long newsId,
        NewsTargetType targetType,
        long targetId,
        NewsRelationMethod relationMethod,
        BigDecimal confidenceScore,
        NewsRelationStatus relationStatus,
        String reasonSummary) {

    /** 是否可进前台列表与 AI 证据（契约 §11.2：只有 {@code CONFIRMED} 可用）。 */
    public boolean confirmed() {
        return relationStatus == NewsRelationStatus.CONFIRMED;
    }
}
