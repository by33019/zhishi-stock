package cn.zhishi.stock.news.domain;

/**
 * 稿件内容状态，与 {@code stock_news.content_status} 的 CHECK 约束同集合。
 *
 * <p>契约 §11.2：仅返回 {@code PUBLISHED}；撤稿（{@code WITHDRAWN}）保留元数据时
 * 必须显著标记、不继续展示摘要。因此本枚举的语义差异直接决定"列表里有没有它"。
 */
public enum NewsContentStatus {

    /** 已发布，可展示。 */
    PUBLISHED,

    /** 已撤稿：元数据保留（按 id 取详情时返回 {@code NEWS_WITHDRAWN}），但不进任何列表。 */
    WITHDRAWN,

    /** 已删除。 */
    DELETED
}
