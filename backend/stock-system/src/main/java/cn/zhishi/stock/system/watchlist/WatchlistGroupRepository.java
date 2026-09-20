package cn.zhishi.stock.system.watchlist;

import java.util.List;
import java.util.Optional;

/**
 * 自选分组的持久化端口。
 *
 * <p>只提供"存储原语"，不含任何业务判断：唯一约束冲突由实现抛
 * {@code org.springframework.dao.DuplicateKeyException}，由用例层决定它意味着什么
 * （新建分组时是"名字重复"，建默认分组时是"已经有了"）。
 */
public interface WatchlistGroupRepository {

    /** 本人全部有效分组，按 {@code sortNo}、{@code groupId} 升序。 */
    List<WatchlistGroup> findActiveByUser(long userId);

    Optional<WatchlistGroup> findActive(long userId, long groupId);

    /** 下一个可用的 {@code sortNo}（无分组时为 0）。 */
    int nextSortNo(long userId);

    /** 插入新分组；唯一索引冲突时抛 {@code DuplicateKeyException}。 */
    void insert(WatchlistGroup group);

    /**
     * 条件改名：只有 {@code version == expectedVersion} 且未被软删时才生效。
     *
     * @return 影响行数是否为 1；{@code false} 表示版本不匹配或行已消失
     */
    boolean rename(long userId, long groupId, String groupName, int expectedVersion);

    /** 条件软删：只有 {@code version == expectedVersion} 且未被软删时才生效。 */
    boolean softDelete(long userId, long groupId, int expectedVersion);

    /**
     * 原子重排：第 i 个分组的 {@code sortNo} 置为 i，并 {@code version + 1}。
     *
     * <p>必须由调用方放在事务里；不做任何集合合法性校验（那是用例层的事）。
     */
    void reorder(long userId, List<Long> groupIds);

    /**
     * 把源组的自选项搬到目标组，先合并掉目标组已有的同证券。
     *
     * @return 真正搬到目标组的条数（不含被合并掉的重复项）
     */
    int moveItems(long userId, long sourceGroupId, long targetGroupId);
}
