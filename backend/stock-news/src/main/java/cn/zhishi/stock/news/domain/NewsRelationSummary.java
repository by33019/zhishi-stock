package cn.zhishi.stock.news.domain;

import java.math.BigDecimal;

/**
 * {@code NewsSummary.relations} 的元素：契约 §4.3 说的"确认的证券、板块或市场关联摘要"。
 *
 * <p>{@code targetId} 是**对外标识**（{@code sim-600519} / {@code sim-bk0001} / {@code CN}），
 * 不是库里的 bigint 代理键——契约与前端全程用字符串 ID，返回代理键会让跳转 404。
 * 这正是 M2-06 / M2-11 反复踩过的那类缺陷："写死的标识符只要需要被别处解析，就是缺陷"。
 *
 * @param targetCode 便于展示的短代码（证券代码 / 板块代码 / 市场代码）
 * @param targetName 证券简称 / 板块名 / 市场名
 */
public record NewsRelationSummary(
        NewsTargetType targetType,
        String targetId,
        String targetCode,
        String targetName,
        NewsRelationMethod relationMethod,
        BigDecimal confidenceScore) {
}
