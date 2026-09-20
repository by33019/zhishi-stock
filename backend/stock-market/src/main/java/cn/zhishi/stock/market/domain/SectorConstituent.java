package cn.zhishi.stock.market.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 板块成分股（{@code RESTful-API.md} §10 SEC-06 的每项）。
 *
 * <p>契约写「{@code PageData<QuoteSnapshot>}；每项附 {@code relationType}、
 * {@code isPrimary}、{@code contributionRank}」。形状选**嵌套 {@code quote}**：
 * 把 16 个快照字段摊平到每项上需要再造一个 19 字段的记录，于是
 * {@link QuoteSnapshot} 的字段增删要同步两处，且不会有任何测试变红。
 *
 * <p>{@code contributionRank}：该成分股在板块内按涨跌幅降序的序号（从 1 开始）；
 * 无有效行情的成分股（停牌等）排在有效项之后，按 {@code fullSymbol} 升序续号。
 * 它是**数据属性**，不受 SEC-06 的 {@code rankingType} 影响——
 * 否则"按跌幅排序"时贡献度排名会看起来是反的。
 *
 * <p>{@code isPrimary} 显式声明 JSON 名，不依赖 Jackson 对 {@code is} 前缀的启发式推断
 * （同 {@code SecuritySummary} 的处理）。
 */
public record SectorConstituent(
        QuoteSnapshot quote,
        String relationType,
        @JsonProperty("isPrimary") boolean isPrimary,
        int contributionRank) {
}
