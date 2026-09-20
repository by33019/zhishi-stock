package cn.zhishi.stock.system.watchlist;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * WAT-01~WAT-05 的用例层：契约 §12.1 / §12.3 的全部业务规则都在这里，且只在这里。
 *
 * <p>写方法标 {@code @Transactional}：WAT-04（搬移 + 软删）与 WAT-05（N 条排序）
 * 必须落在同一事务里，否则会出现"搬了没删"或"改了一半顺序"。
 *
 * <p><b>为什么唯一约束冲突在这里翻译、而不是在仓储实现里</b>：同一个
 * {@code DuplicateKeyException} 在不同用例里含义不同——{@link #create} 里是"分组名重复"，
 * {@link #createDefaultGroup} 里是"默认分组已经有了（无需报错）"。
 * 仓储保持"哑存储"、由用例决定语义，才不会把业务判断写进适配器。
 */
public class WatchlistGroupService {

    /** 注册后自动创建的分组名。契约没有规定显示名，因此全项目只此一处定义。 */
    public static final String DEFAULT_GROUP_NAME = "默认分组";

    private final WatchlistGroupRepository groups;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public WatchlistGroupService(
            WatchlistGroupRepository groups, LongSupplier idGenerator, Clock clock) {
        this.groups = groups;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** WAT-01。 */
    public List<WatchlistGroup> list(long userId) {
        return groups.findActiveByUser(userId);
    }

    /** WAT-02。 */
    @Transactional
    public CreatedGroup create(long userId, String rawName) {
        WatchlistGroupName name = WatchlistGroupName.of(rawName);
        WatchlistGroup group = new WatchlistGroup(
                idGenerator.getAsLong(),
                userId,
                name.value(),
                groups.nextSortNo(userId),
                false,
                0,
                0);
        try {
            groups.insert(group);
        } catch (DuplicateKeyException exception) {
            throw nameExists();
        }
        return new CreatedGroup(group, now());
    }

    /**
     * 注册后创建默认分组（契约 §12.3）。
     *
     * <p>幂等：已有有效默认分组时直接返回它。AUTH-03 注册流程落地时必须调用本方法；
     * 当前由 {@code DevelopmentAccountSeeder} 调用，让 dev / test 环境的数据与"注册之后"一致。
     */
    @Transactional
    public WatchlistGroup createDefaultGroup(long userId) {
        Optional<WatchlistGroup> existing = findDefaultGroup(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        WatchlistGroup group = new WatchlistGroup(
                idGenerator.getAsLong(), userId, DEFAULT_GROUP_NAME, 0, true, 0, 0);
        try {
            groups.insert(group);
        } catch (DuplicateKeyException exception) {
            // 并发下另一个请求已经建好了：读回来即可，不报错——本方法的语义就是"确保有且只有一个"。
        }
        return findDefaultGroup(userId).orElseThrow(
                () -> new IllegalStateException("默认分组创建失败"));
    }

    /** WAT-03。 */
    @Transactional
    public WatchlistGroup rename(long userId, long groupId, String rawName, int expectedVersion) {
        WatchlistGroupName name = WatchlistGroupName.of(rawName);
        requireActive(userId, groupId);
        boolean renamed;
        try {
            renamed = groups.rename(userId, groupId, name.value(), expectedVersion);
        } catch (DuplicateKeyException exception) {
            throw nameExists();
        }
        if (!renamed) {
            throw versionConflict();
        }
        return requireActive(userId, groupId);
    }

    /** WAT-04。 */
    @Transactional
    public DeleteResult delete(
            long userId, long groupId, int expectedVersion, Long moveItemsToGroupId) {
        WatchlistGroup group = requireActive(userId, groupId);
        if (group.isDefault()) {
            throw new WatchlistException(
                    WatchlistErrorCode.DEFAULT_GROUP_CANNOT_DELETE, "默认分组不可删除");
        }
        if (group.itemCount() == 0) {
            // 空组：契约只要求"删除非空组时必填"，客户端无法可靠知道组是否为空，
            // 因此这里忽略传入的目标组，而不是报错把它变成"先查再删"的两步操作。
            softDeleteOrConflict(userId, groupId, expectedVersion);
            return new DeleteResult(true, 0);
        }
        if (moveItemsToGroupId == null) {
            throw new WatchlistException(
                    WatchlistErrorCode.TARGET_GROUP_REQUIRED,
                    "分组内仍有自选项，删除前必须用 moveItemsToGroupId 指定接收分组");
        }
        if (moveItemsToGroupId == groupId) {
            throw new WatchlistException(
                    WatchlistErrorCode.TARGET_GROUP_CONFLICT, "目标分组不能是待删除的分组本身");
        }
        WatchlistGroup target = groups.findActive(userId, moveItemsToGroupId)
                .orElseThrow(() -> new WatchlistException(
                        WatchlistErrorCode.GROUP_NOT_FOUND, "目标分组不存在"));
        softDeleteOrConflict(userId, groupId, expectedVersion);
        return new DeleteResult(true, groups.moveItems(userId, groupId, target.groupId()));
    }

    /** WAT-05。 */
    @Transactional
    public List<WatchlistGroup> reorder(long userId, List<Long> groupIds) {
        List<WatchlistGroup> current = groups.findActiveByUser(userId);
        Set<Long> currentIds = current.stream()
                .map(WatchlistGroup::groupId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (groupIds == null
                || groupIds.size() != currentIds.size()
                || new HashSet<>(groupIds).size() != groupIds.size()
                || !currentIds.equals(new HashSet<>(groupIds))) {
            throw new WatchlistException(
                    WatchlistErrorCode.INVALID_REQUEST,
                    "groupIds 必须恰好包含本人全部有效分组且不重复");
        }
        groups.reorder(userId, groupIds);
        return groups.findActiveByUser(userId);
    }

    private Optional<WatchlistGroup> findDefaultGroup(long userId) {
        return groups.findActiveByUser(userId).stream()
                .filter(WatchlistGroup::isDefault)
                .findFirst();
    }

    private WatchlistGroup requireActive(long userId, long groupId) {
        return groups.findActive(userId, groupId)
                .orElseThrow(() -> new WatchlistException(
                        WatchlistErrorCode.RESOURCE_NOT_FOUND, "分组不存在"));
    }

    private void softDeleteOrConflict(long userId, long groupId, int expectedVersion) {
        if (!groups.softDelete(userId, groupId, expectedVersion)) {
            throw versionConflict();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static WatchlistException nameExists() {
        return new WatchlistException(WatchlistErrorCode.GROUP_NAME_EXISTS, "分组名称已存在");
    }

    private static WatchlistException versionConflict() {
        return new WatchlistException(
                WatchlistErrorCode.VERSION_CONFLICT, "分组已被其他会话修改，请刷新后重试");
    }
}
