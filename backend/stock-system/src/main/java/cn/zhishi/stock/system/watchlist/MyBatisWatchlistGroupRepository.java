package cn.zhishi.stock.system.watchlist;

import java.util.List;
import java.util.Optional;

/**
 * {@link WatchlistGroupRepository} 的 MyBatis 实现。
 *
 * <p>不含任何业务判断：唯一约束冲突原样抛出 {@code DuplicateKeyException}，
 * 由用例层决定它意味着什么（见 {@link WatchlistGroupService} 的类注释）。
 */
public class MyBatisWatchlistGroupRepository implements WatchlistGroupRepository {

    private final WatchlistGroupMapper mapper;

    public MyBatisWatchlistGroupRepository(WatchlistGroupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<WatchlistGroup> findActiveByUser(long userId) {
        return mapper.findActiveByUser(userId).stream()
                .map(MyBatisWatchlistGroupRepository::toDomain)
                .toList();
    }

    @Override
    public Optional<WatchlistGroup> findActive(long userId, long groupId) {
        return Optional.ofNullable(mapper.findActive(userId, groupId))
                .map(MyBatisWatchlistGroupRepository::toDomain);
    }

    @Override
    public int nextSortNo(long userId) {
        return mapper.nextSortNo(userId);
    }

    @Override
    public void insert(WatchlistGroup group) {
        mapper.insert(
                group.groupId(),
                group.userId(),
                group.groupName(),
                group.sortNo(),
                group.isDefault());
    }

    @Override
    public boolean rename(long userId, long groupId, String groupName, int expectedVersion) {
        return mapper.rename(userId, groupId, groupName, expectedVersion) == 1;
    }

    @Override
    public boolean softDelete(long userId, long groupId, int expectedVersion) {
        return mapper.softDelete(userId, groupId, expectedVersion) == 1;
    }

    @Override
    public void reorder(long userId, List<Long> groupIds) {
        for (int index = 0; index < groupIds.size(); index++) {
            mapper.updateSortNo(userId, groupIds.get(index), index);
        }
    }

    @Override
    public int moveItems(long userId, long sourceGroupId, long targetGroupId) {
        // 先合并掉目标组已有的同证券，否则下面的整体搬迁会撞唯一索引。
        mapper.deleteItemsAlreadyInTarget(userId, sourceGroupId, targetGroupId);
        return mapper.moveItems(userId, sourceGroupId, targetGroupId);
    }

    private static WatchlistGroup toDomain(WatchlistGroupRow row) {
        return new WatchlistGroup(
                row.groupId(),
                row.userId(),
                row.groupName(),
                row.sortNo(),
                row.isDefault(),
                row.version(),
                row.itemCount());
    }
}
