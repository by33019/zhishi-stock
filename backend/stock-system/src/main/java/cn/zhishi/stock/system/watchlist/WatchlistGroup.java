package cn.zhishi.stock.system.watchlist;

/**
 * 自选分组。
 *
 * <p><b>刻意不含 {@code createdAt}</b>：表里的 {@code created_at} 由
 * {@code DEFAULT CURRENT_TIMESTAMP(3)} 填充，其字面值取决于 MySQL 会话时区
 * （compose 里是 Asia/Shanghai，Testcontainers 里是容器默认值），
 * 读回来再做时区换算必然有一处是错的。
 * 接口要返回的"创建时间"在应用侧本来就有（{@code Clock}），因此 WAT-02 的 {@code createdAt}
 * 由 {@link WatchlistGroupService} 用应用时钟产出（见 {@link CreatedGroup}），
 * 表里的列只作审计用途，不进领域模型。
 */
public record WatchlistGroup(
        long groupId,
        long userId,
        String groupName,
        int sortNo,
        boolean isDefault,
        int version,
        int itemCount) {
}
