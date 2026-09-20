package cn.zhishi.stock.system.watchlist;

import java.time.LocalDateTime;

/**
 * {@code user_watchlist_item} 的行映射结果，与 {@link WatchlistItem} 字段一一对应。
 *
 * <p>与 M3-01 的 {@code WatchlistGroupRow} 同样刻意分开：SQL 的列别名只约束本记录，
 * 领域记录的字段顺序与命名可以独立演进，不会因为改一条 SELECT 而波及业务代码。
 */
public record WatchlistItemRow(
        long itemId,
        long userId,
        long groupId,
        long securityId,
        int sortNo,
        int version,
        LocalDateTime createdAt) {

    WatchlistItem toItem() {
        return new WatchlistItem(itemId, userId, groupId, securityId, sortNo, version, createdAt);
    }
}
