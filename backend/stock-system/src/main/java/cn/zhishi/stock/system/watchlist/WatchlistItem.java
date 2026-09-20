package cn.zhishi.stock.system.watchlist;

import java.time.LocalDateTime;

/**
 * 自选项（{@code user_watchlist_item} 的一行）。
 *
 * <p>{@code securityId} 是**持久化代理键**（bigint），不是契约里的字符串标识；
 * 两者的映射见 {@code cn.zhishi.stock.market.domain.SecurityIdentityProvider}。
 * 领域层刻意保留代理键形态，避免每一层都做一次正反转换。
 *
 * <p>{@code createdAt} 是 {@link LocalDateTime}（库里的墙上时间）而不是
 * {@code OffsetDateTime}：写入由应用按 {@code Clock} 的时区决定，回读也按同一个时区解释，
 * 转换只在 {@link WatchlistItemService} 一处发生——那里才需要对外给带偏移的时间。
 * M3-01 的分组之所以干脆不读 {@code created_at}，就是因为列默认值的墙上时间取决于
 * MySQL 会话时区；自选项的契约要求回显 {@code createdAt}，因此改由应用显式写入。
 */
public record WatchlistItem(
        long itemId,
        long userId,
        long groupId,
        long securityId,
        int sortNo,
        int version,
        LocalDateTime createdAt) {
}
