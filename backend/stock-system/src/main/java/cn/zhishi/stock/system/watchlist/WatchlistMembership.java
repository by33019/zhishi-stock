package cn.zhishi.stock.system.watchlist;

/**
 * WAT-12 的 Map 值：某只证券在自选里的落点。
 *
 * <p>契约把响应定义成 {@code securityId -> {groupId, groupName, itemId}} 的**单值** Map，
 * 而同一证券可以同时存在于多个分组（WAT-07 明确允许）。信息损失是契约带来的，
 * 取哪一条由 {@link WatchlistItemService#membership} 规定：
 * 分组 {@code sortNo}、{@code groupId} 升序的第一条——与 WAT-01 的分组顺序同源，
 * 于是默认分组（{@code sortNo = 0}）自然优先。
 */
public record WatchlistMembership(long groupId, String groupName, long itemId) {
}
