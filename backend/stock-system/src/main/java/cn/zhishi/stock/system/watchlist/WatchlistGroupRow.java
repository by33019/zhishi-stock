package cn.zhishi.stock.system.watchlist;

/**
 * {@code user_watchlist_group} 的行映射。
 *
 * <p>与 {@code SysUserRecord} 一样，SQL 里给每一列都写了显式别名，
 * 因此不依赖 {@code mapUnderscoreToCamelCase} 配置。
 */
public record WatchlistGroupRow(
        long groupId,
        long userId,
        String groupName,
        int sortNo,
        boolean isDefault,
        int version,
        int itemCount) {
}
