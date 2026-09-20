package cn.zhishi.stock.news.domain;

/**
 * 去重状态，与 {@code stock_news.dedup_status} 的 CHECK 约束同集合。
 *
 * <p>契约 §11.2：{@code DUPLICATE} 的稿件默认折叠到 {@code canonicalNewsId}，
 * 避免同一事件重复刷屏。因此**所有前台列表都只返回 {@code ORIGINAL}**。
 *
 * <p>与 {@code ck_stock_news_canonical} 配套的不变量：
 * {@code ORIGINAL ⇒ canonicalNewsId IS NULL}，
 * {@code DUPLICATE ⇒ canonicalNewsId IS NOT NULL 且 ≠ 自身 id}。
 * 该不变量由 {@link NewsDeduplicator} 唯一实现，库约束是第二道防线。
 */
public enum NewsDedupStatus {

    /** 主记录：没有被判定为重复。 */
    ORIGINAL,

    /** 重复稿：内容指纹命中了一条已有主记录。 */
    DUPLICATE
}
