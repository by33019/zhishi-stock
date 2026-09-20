package cn.zhishi.stock.news.domain;

/**
 * 原文可访问状态，与 {@code stock_news.original_access_status} 的 CHECK 约束同集合。
 *
 * <p>与"平台有没有这条资讯"无关，只描述**原文链接现在还能不能打开**。
 * 契约 §11.2 要求只放行白名单协议（{@code https} 等），前端新窗口打开并带
 * {@code noopener noreferrer}；本字段是给用户看的"链接可能已失效"提示。
 */
public enum NewsOriginalAccessStatus {

    /** 上次校验时原文可访问。 */
    AVAILABLE,

    /** 上次校验时原文已不可访问（下线、付费墙、404）。 */
    UNAVAILABLE,

    /** 尚未校验过——**不是**"可访问"，也不是"不可访问"。 */
    UNKNOWN
}
