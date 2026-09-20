package cn.zhishi.stock.news.domain;

/**
 * 关联的产生方式，与 {@code stock_news_relation.relation_method} 的 CHECK 约束同集合。
 *
 * <p>区分"来源直接给的"与"我们推断的"是**可审计性**要求：
 * 一条 {@code EXPLICIT} 关联出错是来源的问题，一条 {@code RULE} 关联出错是我们的规则问题，
 * 两者的排查方向完全不同。后台复核界面按本字段分组（M3-11）。
 */
public enum NewsRelationMethod {

    /** 来源侧结构化字段直接给出（无需推断）。 */
    EXPLICIT,

    /** 由本地规则推断（标题/摘要文本匹配）。 */
    RULE,

    /** 由模型推断。MVP 未启用——留位以免将来新增取值时要改库约束。 */
    MODEL,

    /** 人工指定或人工改判。 */
    MANUAL
}
