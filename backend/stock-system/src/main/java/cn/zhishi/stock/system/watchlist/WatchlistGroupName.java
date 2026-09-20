package cn.zhishi.stock.system.watchlist;

/**
 * 分组名：**"什么是一个合法的分组名"的唯一实现**。
 *
 * <p>数据库的 {@code ck_watchlist_group_name CHECK (CHAR_LENGTH(TRIM(group_name)) BETWEEN 1 AND 20)}
 * 是同一个事实的另一个副本，两者必须给出相同判定，因此这里的两处细节是刻意的：
 *
 * <ul>
 *   <li>用 {@code trim()} 而不是 {@code strip()}：MySQL 的 {@code TRIM()} 默认只去空格，
 *       Java 的 {@code trim()} 只去 {@code <= U+0020}，两者对空格行为一致；
 *       而 {@code strip()} 会去掉 Unicode 空白，比数据库更宽，会出现"应用层放行、数据库 CHECK 拒绝"的 500。
 *   <li>用 {@code codePointCount} 而不是 {@code length()}：数据库用 {@code CHAR_LENGTH()} 数**字符**，
 *       Java 的 {@code length()} 数 UTF-16 码元。一个 emoji 在 Java 里算 2、在 MySQL 里算 1，
 *       用 {@code length()} 会让"20 个字符的合法名字"被数据库拒绝。
 * </ul>
 *
 * <p>规范化与校验都写在紧凑构造器里，因此**任何构造路径**（不只是 {@link #of}）
 * 都拿不到未 trim 或超长的值——这个类型无法表示非法状态。
 */
public record WatchlistGroupName(String value) {

    public static final int MAX_LENGTH = 20;

    public WatchlistGroupName {
        String trimmed = value == null ? "" : value.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > MAX_LENGTH) {
            throw new WatchlistException(
                    WatchlistErrorCode.GROUP_NAME_INVALID,
                    "分组名称去除首尾空白后需为 1 至 " + MAX_LENGTH + " 个字符");
        }
        value = trimmed;
    }

    public static WatchlistGroupName of(String raw) {
        return new WatchlistGroupName(raw);
    }
}
