package cn.zhishi.stock.system.watchlist;

/**
 * WAT-09 的结果：移动后的自选项 + 是否发生了合并。
 *
 * <p>合并时 {@code item} 是**存活下来的那条**（目标组原有的项），源项已被删除。
 * 返回源项的 id 会指向一个不存在的资源，客户端下一次操作就会 404。
 */
public record MovedItem(WatchlistItem item, boolean merged) {
}
