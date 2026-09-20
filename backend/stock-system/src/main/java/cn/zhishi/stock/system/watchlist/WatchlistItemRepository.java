package cn.zhishi.stock.system.watchlist;

import java.util.List;
import java.util.Optional;

/**
 * 自选项的持久化端口。
 *
 * <p>与 {@link WatchlistGroupRepository} 同样只提供"存储原语"：
 * 唯一索引冲突由实现抛 {@code DuplicateKeyException}，由用例层决定它意味着什么
 * （WAT-07 里是"同组同证券已存在，返回已存在的那条"，而不是报错）。
 *
 * <p>所有条件写都**同时**带 {@code user_id} 与 {@code version}：
 * 前者让"删到别人的行"在 SQL 层就不可能，后者让并发修改必然命中 0 行。
 */
public interface WatchlistItemRepository {

    /** 某分组内的全部自选项，按 {@code sortNo}、{@code itemId} 升序。 */
    List<WatchlistItem> findByGroup(long userId, long groupId);

    /** 本人全部自选项，先按 {@code groupId}、再按 {@code sortNo} 升序（WAT-11 用）。 */
    List<WatchlistItem> findByUser(long userId);

    Optional<WatchlistItem> find(long userId, long groupId, long itemId);

    /** 同组内是否已有该证券（WAT-09 判断"该合并还是该搬"）。 */
    Optional<WatchlistItem> findBySecurity(long userId, long groupId, long securityId);

    /** 该组下一个可用的 {@code sortNo}（空组时为 0）。 */
    int nextSortNo(long userId, long groupId);

    /** 插入新自选项；同组同证券撞唯一索引时抛 {@code DuplicateKeyException}。 */
    void insert(WatchlistItem item);

    /** 无条件删除（WAT-08 没有 If-Match）。@return 是否真的删掉了一行 */
    boolean delete(long userId, long groupId, long itemId);

    /** 条件删除（WAT-09 的合并路径）。@return 是否真的删掉了一行 */
    boolean deleteIfVersion(long userId, long groupId, long itemId, int expectedVersion);

    /** 条件搬到目标分组并落新序号。@return 影响行数是否为 1 */
    boolean moveToGroup(
            long userId,
            long sourceGroupId,
            long itemId,
            long targetGroupId,
            int sortNo,
            int expectedVersion);

    /** 条件改序号（WAT-10 的逐条写入）。@return 影响行数是否为 1 */
    boolean updateSortNo(long userId, long groupId, long itemId, int sortNo, int expectedVersion);
}
