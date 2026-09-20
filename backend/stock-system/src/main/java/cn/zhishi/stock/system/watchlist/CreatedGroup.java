package cn.zhishi.stock.system.watchlist;

import java.time.OffsetDateTime;

/**
 * 新建分组的结果：分组本身 + 应用时钟记录的创建时刻。
 *
 * <p>为什么不从表里读 {@code created_at}：见 {@link WatchlistGroup} 的说明。
 */
public record CreatedGroup(WatchlistGroup group, OffsetDateTime createdAt) {
}
