package cn.zhishi.stock.market.domain;

/**
 * 一条搜索命中。
 *
 * @param security    命中的证券
 * @param matchedField 命中的字段，用于前端提示"为什么它被搜出来"
 * @param highlight   命中字段中的**实际命中子串原文**（保留原始大小写）。
 *                    刻意不返回 HTML 标记：标记交给前端，避免把转义问题引进后端。
 */
public record SecuritySearchMatch(
        SecuritySummary security,
        MatchedField matchedField,
        String highlight) {

    /**
     * 命中字段。声明顺序即匹配优先级（{@code CODE} 最高）。
     *
     * <p>{@code PINYIN} / {@code PINYIN_ABBR} 当前无数据可用，但保留为完整能力：
     * 匹配分支与单测都已就位，真实主数据接入后无需改动逻辑。
     */
    public enum MatchedField {
        CODE,
        NAME,
        PINYIN,
        PINYIN_ABBR
    }
}
