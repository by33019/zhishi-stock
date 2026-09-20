package cn.zhishi.stock.system.watchlist;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link WatchlistItemRepository} 的 MyBatis 实现：**哑存储**，不做任何业务判断。
 *
 * <p>唯一索引冲突原样抛 {@link DuplicateKeyException}，把"它意味着什么"留给用例层——
 * WAT-07 里它是"已经加过这只证券，返回已存在的那条"，而不是错误。
 *
 * <p>条件写统一用"影响行数是否为 1"翻译成布尔：{@code false} 表示版本不匹配或行已消失，
 * 两种情况对调用方的含义相同（本次操作没有生效）。
 */
public class MyBatisWatchlistItemRepository implements WatchlistItemRepository {

    private final WatchlistItemMapper mapper;

    public MyBatisWatchlistItemRepository(WatchlistItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<WatchlistItem> findByGroup(long userId, long groupId) {
        return mapper.findByGroup(userId, groupId).stream().map(WatchlistItemRow::toItem).toList();
    }

    @Override
    public List<WatchlistItem> findByUser(long userId) {
        return mapper.findByUser(userId).stream().map(WatchlistItemRow::toItem).toList();
    }

    @Override
    public Optional<WatchlistItem> find(long userId, long groupId, long itemId) {
        return Optional.ofNullable(mapper.find(userId, groupId, itemId)).map(WatchlistItemRow::toItem);
    }

    @Override
    public Optional<WatchlistItem> findBySecurity(long userId, long groupId, long securityId) {
        return Optional.ofNullable(mapper.findBySecurity(userId, groupId, securityId))
                .map(WatchlistItemRow::toItem);
    }

    @Override
    public int nextSortNo(long userId, long groupId) {
        return mapper.nextSortNo(userId, groupId);
    }

    @Override
    public void insert(WatchlistItem item) {
        mapper.insert(
                item.itemId(),
                item.userId(),
                item.groupId(),
                item.securityId(),
                item.sortNo(),
                item.createdAt());
    }

    @Override
    public boolean delete(long userId, long groupId, long itemId) {
        return mapper.delete(userId, groupId, itemId) > 0;
    }

    @Override
    public boolean deleteIfVersion(long userId, long groupId, long itemId, int expectedVersion) {
        return mapper.deleteIfVersion(userId, groupId, itemId, expectedVersion) > 0;
    }

    @Override
    public boolean moveToGroup(
            long userId,
            long sourceGroupId,
            long itemId,
            long targetGroupId,
            int sortNo,
            int expectedVersion) {
        return mapper.moveToGroup(
                        userId, sourceGroupId, itemId, targetGroupId, sortNo, expectedVersion)
                == 1;
    }

    @Override
    public boolean updateSortNo(
            long userId, long groupId, long itemId, int sortNo, int expectedVersion) {
        return mapper.updateSortNo(userId, groupId, itemId, sortNo, expectedVersion) == 1;
    }
}
