package cn.zhishi.stock.system.watchlist;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteBatch;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * WAT-06~WAT-10 与 WAT-11 / WAT-12 的用例层：契约 §12.2 / §12.3 的业务规则都在这里，且只在这里。
 *
 * <p>写方法标 {@code @Transactional}：WAT-09（合并 = 删一条 + 可能改一条）与
 * WAT-10（N 条排序）必须落在同一事务里，否则会出现"删了没搬"或"改了一半顺序"。
 *
 * <p>本类同时依赖行情域的 {@link SecurityIdentityProvider} 与
 * {@link QuoteSnapshotBatchProvider}——但只依赖 {@code stock-market} 的 **domain**，
 * 不依赖它的用例层（市场状态由 controller 组装进响应）。
 */
public class WatchlistItemService {

    /** 自选是 A 股业务，整批快照固定取 CN。 */
    private static final String MARKET_CODE = "CN";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_MEMBERSHIP_IDS = 50;

    /** 资讯域尚未就位（M3-04）时，每个概览响应都必须说明这一点。 */
    private static final String NEWS_NOT_IMPLEMENTED =
            "最新资讯数尚未实现（资讯 Provider 见 M3-04），latestNewsCount 恒为 null";

    private final WatchlistItemRepository items;
    private final WatchlistGroupRepository groups;
    private final SecurityIdentityProvider securities;
    private final QuoteSnapshotBatchProvider quotes;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public WatchlistItemService(
            WatchlistItemRepository items,
            WatchlistGroupRepository groups,
            SecurityIdentityProvider securities,
            QuoteSnapshotBatchProvider quotes,
            LongSupplier idGenerator,
            Clock clock) {
        this.items = items;
        this.groups = groups;
        this.securities = securities;
        this.quotes = quotes;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** WAT-06。 */
    public PageData<WatchlistEntry> listItems(
            long userId, long groupId, boolean includeQuote, Integer page, Integer size) {
        int effectivePage = validatePage(page);
        int effectiveSize = validatePageSize(size);
        return PageData.slice(
                entriesOf(userId, groupId, includeQuote), effectivePage, effectiveSize);
    }

    /** WAT-06 的无分页版本，供 WAT-01 的 {@code includeItems=true} 复用。 */
    public List<WatchlistEntry> entriesOf(long userId, long groupId, boolean includeQuote) {
        requireActive(userId, groupId);
        return assemble(items.findByGroup(userId, groupId), includeQuote).entries();
    }

    /**
     * WAT-07。
     *
     * <p>同组同证券是**成功幂等**：撞唯一索引后读回已存在的那条并返回 200。
     * 契约原文与 PRD 的验收口径（"重复添加按成功幂等返回 / 重复点击不生成重复记录"）都指向这一点，
     * 因此这里不会抛 {@code WATCHLIST_ITEM_EXISTS}——见 spec §3.5。
     *
     * <p>不用"先查再插"：并发下会双写，唯一索引才是唯一权威。
     */
    @Transactional
    public WatchlistEntry addItem(long userId, long groupId, String securityId) {
        requireActive(userId, groupId);
        SecurityIdentity identity = requireSecurity(securityId);
        WatchlistItem item = new WatchlistItem(
                idGenerator.getAsLong(),
                userId,
                groupId,
                identity.storageId(),
                items.nextSortNo(userId, groupId),
                0,
                LocalDateTime.now(clock));
        try {
            items.insert(item);
        } catch (DuplicateKeyException exception) {
            WatchlistItem existing = items
                    .findBySecurity(userId, groupId, identity.storageId())
                    .orElseThrow(() -> new IllegalStateException(
                            "唯一索引冲突，但按 (分组, 证券) 读不回已存在的自选项"));
            return toEntry(existing, identity, null);
        }
        return toEntry(item, identity, null);
    }

    /**
     * WAT-08。硬删除 + 按 (分组, 自选项) 幂等。
     *
     * <p>{@code deleted} 如实反映是否真的删掉了一行：自选项表没有墓碑，
     * "重复删除"与"itemId 从来不存在"在库层面不可区分，与其恒返回 {@code true}
     * 让客户端以为删掉了什么，不如照实说。
     *
     * <p>越权由 SQL 的 {@code user_id} 条件挡住：他人的 itemId 命中 0 行，
     * 既不会删到别人的数据，也不会泄露它是否存在。
     */
    @Transactional
    public boolean removeItem(long userId, long groupId, long itemId) {
        requireActive(userId, groupId);
        return items.delete(userId, groupId, itemId);
    }

    /**
     * WAT-09。{@code expectedVersion} 是 **item** 的版本（不是分组的）。
     *
     * <p>先校验引用、再条件写：目标组不是本人的、目标就是源组、item 不属于本人，
     * 都在任何写入之前报错，客户端拿到的错误码才指向真正的问题。
     *
     * <p>合并路径刻意分两步（先探测目标组是否已有同证券，再二选一），而不是一条语句里赌：
     * 一条语句两种结果会让"影响 0 行"同时意味着"版本冲突"和"该走合并"，
     * 无法给出正确的错误码。
     */
    @Transactional
    public MovedItem moveItem(
            long userId, long groupId, long itemId, long targetGroupId, int expectedVersion) {
        requireActive(userId, groupId);
        WatchlistItem source = items.find(userId, groupId, itemId)
                .orElseThrow(() -> new WatchlistException(
                        WatchlistErrorCode.RESOURCE_NOT_FOUND, "自选项不存在"));
        if (targetGroupId == groupId) {
            throw new WatchlistException(
                    WatchlistErrorCode.TARGET_GROUP_CONFLICT, "目标分组不能是自选项所在的分组本身");
        }
        requireTargetGroup(userId, targetGroupId);

        Optional<WatchlistItem> survivor =
                items.findBySecurity(userId, targetGroupId, source.securityId());
        if (survivor.isPresent()) {
            if (!items.deleteIfVersion(userId, groupId, itemId, expectedVersion)) {
                throw itemVersionConflict();
            }
            // 返回存活的那条：源项已经不存在，返回它的 id 会让客户端下一次操作 404。
            return new MovedItem(survivor.get(), true);
        }
        int sortNo = items.nextSortNo(userId, targetGroupId);
        if (!items.moveToGroup(userId, groupId, itemId, targetGroupId, sortNo, expectedVersion)) {
            throw itemVersionConflict();
        }
        return new MovedItem(
                items.find(userId, targetGroupId, itemId)
                        .orElseThrow(() -> new IllegalStateException("搬迁成功但读不回自选项")),
                false);
    }

    /**
     * WAT-10。原子重排。
     *
     * <p>"原子"由两件事共同保证：整个方法在一个事务里；每条 {@code UPDATE} 都带
     * {@code WHERE version = 读到的版本}。读到之后、写之前有人改了组内项时，
     * 条件更新命中 0 行 → 抛异常 → 整个事务回滚，不会出现"改了一半的顺序"。
     *
     * <p>集合不匹配返回 **409**（契约明说"并发变化时返回 409 并要求刷新"），
     * 只有"重复"这种请求体本身格式错误的情况才是 400——与 WAT-05 的 400 差异是契约措辞带来的，见 spec §3.8。
     */
    @Transactional
    public List<WatchlistItem> reorderItems(long userId, long groupId, List<Long> itemIds) {
        requireActive(userId, groupId);
        List<WatchlistItem> current = items.findByGroup(userId, groupId);
        if (itemIds == null) {
            throw invalidRequest("itemIds 不可为空");
        }
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw invalidRequest("itemIds 不可重复");
        }
        Set<Long> currentIds = current.stream()
                .map(WatchlistItem::itemId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (currentIds.size() != itemIds.size() || !currentIds.equals(new HashSet<>(itemIds))) {
            throw staleItemSet();
        }
        Map<Long, WatchlistItem> byId = current.stream()
                .collect(Collectors.toMap(WatchlistItem::itemId, item -> item));
        for (int index = 0; index < itemIds.size(); index++) {
            WatchlistItem item = byId.get(itemIds.get(index));
            if (!items.updateSortNo(userId, groupId, item.itemId(), index, item.version())) {
                throw staleItemSet();
            }
        }
        return items.findByGroup(userId, groupId);
    }

    /**
     * WAT-11。{@code groupId} 为空表示全部有效分组。
     *
     * <p>降级不失败：缺主数据、缺行情、缺资讯都只进 {@link WatchlistOverview#limitations()}，
     * 条目一律保留（PRD："部分行情失败时保留股票并局部提示，不得自动移除"）。
     */
    public WatchlistOverview overview(long userId, Long groupId) {
        List<WatchlistGroup> activeGroups = groups.findActiveByUser(userId);
        Assembled assembled;
        if (groupId == null) {
            assembled = assemble(orderedItems(userId, activeGroups), true);
        } else {
            WatchlistGroup group = requireActive(userId, groupId);
            assembled = assemble(items.findByGroup(userId, group.groupId()), true);
        }
        QuoteBatch batch = assembled.batch();
        return new WatchlistOverview(
                activeGroups,
                assembled.entries(),
                batch == null ? "" : batch.version(),
                batch == null ? MarketOverview.DataStatus.UNAVAILABLE : batch.dataStatus(),
                batch == null ? null : batch.dataTime(),
                limitations(assembled.entries()));
    }

    /**
     * WAT-12。
     *
     * <p>契约把响应定义成单值 Map，而同一证券可以同时在多个分组里，
     * 因此取**分组顺序（{@code sortNo}、{@code groupId}）的第一条**——与 WAT-01 的分组顺序同源，
     * 默认分组自然优先。不在自选里的 {@code securityId} 静默缺席，不占位、不报错。
     */
    public Map<String, WatchlistMembership> membership(long userId, String securityIds) {
        List<String> requested = parseSecurityIds(securityIds);
        Map<String, SecurityIdentity> identities = securities.resolveAll(requested);
        if (identities.isEmpty()) {
            return Map.of();
        }
        Map<Long, SecurityIdentity> byStorageId = new HashMap<>();
        for (SecurityIdentity identity : identities.values()) {
            byStorageId.put(identity.storageId(), identity);
        }
        List<WatchlistGroup> activeGroups = groups.findActiveByUser(userId);
        Map<Long, String> groupNames = new LinkedHashMap<>();
        for (WatchlistGroup group : activeGroups) {
            groupNames.put(group.groupId(), group.groupName());
        }
        Map<String, WatchlistMembership> membership = new LinkedHashMap<>();
        for (WatchlistItem item : orderedItems(userId, activeGroups)) {
            SecurityIdentity identity = byStorageId.get(item.securityId());
            if (identity == null || membership.containsKey(identity.securityId())) {
                continue;
            }
            membership.put(
                    identity.securityId(),
                    new WatchlistMembership(
                            item.groupId(), groupNames.get(item.groupId()), item.itemId()));
        }
        // 保留插入顺序（即分组顺序），便于前端按稳定顺序渲染"已自选"标记。
        return Collections.unmodifiableMap(membership);
    }

    // ---------- 装配 ----------

    /** 一次装配的结果：条目 + 本次用到的批次（概览需要批次自身的版本与时效）。 */
    private record Assembled(List<WatchlistEntry> entries, QuoteBatch batch) {
    }

    /**
     * 把库里的行装配成对外条目。
     *
     * <p>空列表直接返回，**不发起整批取数**：一次 5149 只证券的快照生成换不来任何东西。
     * 由此概览在"没有任何自选项"时的数据状态按 {@link QuoteBatch} 的空批次口径为
     * {@code UNAVAILABLE}，而不是谎称有数据。
     */
    private Assembled assemble(List<WatchlistItem> stored, boolean includeQuote) {
        if (stored.isEmpty()) {
            return new Assembled(List.of(), null);
        }
        Set<Long> storageIds = stored.stream()
                .map(WatchlistItem::securityId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SecurityIdentity> identities = securities.findByStorageIds(storageIds);
        QuoteBatch batch = includeQuote ? QuoteBatch.of(quotes.fetchBatch(MARKET_CODE)) : null;
        return new Assembled(
                stored.stream()
                        .map(item -> toEntry(item, identities.get(item.securityId()), batch))
                        .toList(),
                batch);
    }

    private WatchlistEntry toEntry(
            WatchlistItem item, SecurityIdentity identity, QuoteBatch batch) {
        SecuritySummary summary = identity == null ? null : identity.summary();
        QuoteSnapshot quote = identity == null || batch == null
                ? null
                : batch.snapshotOf(identity.securityId()).orElse(null);
        return new WatchlistEntry(
                item.itemId(),
                item.groupId(),
                item.securityId(),
                item.sortNo(),
                item.version(),
                toOffsetDateTime(item.createdAt()),
                summary,
                quote,
                // 资讯域未就位（M3-04）：null 表示"不知道"，0 会谎称"没有资讯"。
                null);
    }

    /**
     * 本人全部自选项，按**分组顺序**（{@code sortNo}、{@code groupId}）再按组内 {@code sortNo} 排序。
     *
     * <p>{@code findByUser} 的 SQL 只按 {@code group_id} 排（走索引），分组顺序是业务属性，
     * 因此在这里统一重排一次。WAT-11 与 WAT-12 共用本方法，两处的"展示顺序"不会分叉。
     *
     * <p>已软删分组的残留项被过滤掉：分组删除时自选项会被搬走，
     * 但"表里不该有"不等于"表里一定没有"，展示层不该把孤儿项画出来。
     */
    private List<WatchlistItem> orderedItems(long userId, List<WatchlistGroup> activeGroups) {
        if (activeGroups.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> groupOrder = new HashMap<>();
        for (int index = 0; index < activeGroups.size(); index++) {
            groupOrder.put(activeGroups.get(index).groupId(), index);
        }
        return items.findByUser(userId).stream()
                .filter(item -> groupOrder.containsKey(item.groupId()))
                .sorted(Comparator
                        .comparingInt((WatchlistItem item) -> groupOrder.get(item.groupId()))
                        .thenComparingInt(WatchlistItem::sortNo)
                        .thenComparingLong(WatchlistItem::itemId))
                .toList();
    }

    private static List<String> limitations(List<WatchlistEntry> entries) {
        long dangling = entries.stream().filter(entry -> entry.security() == null).count();
        long missingQuotes = entries.stream()
                .filter(entry -> entry.security() != null && entry.quote() == null)
                .count();
        List<String> limitations = new ArrayList<>();
        if (dangling > 0) {
            limitations.add(dangling + " 只自选证券不在证券主数据中，仅返回自选关系");
        }
        if (missingQuotes > 0) {
            limitations.add(missingQuotes + " 只自选证券当前没有行情快照，已保留自选关系");
        }
        limitations.add(NEWS_NOT_IMPLEMENTED);
        return List.copyOf(limitations);
    }

    // ---------- 校验 ----------

    private static List<String> parseSecurityIds(String securityIds) {
        if (securityIds == null) {
            throw invalidRequest("securityIds 必须为 1 至 " + MAX_MEMBERSHIP_IDS + " 个");
        }
        List<String> tokens = new ArrayList<>();
        for (String token : securityIds.split(",", -1)) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        if (tokens.isEmpty() || tokens.size() > MAX_MEMBERSHIP_IDS) {
            throw invalidRequest("securityIds 必须为 1 至 " + MAX_MEMBERSHIP_IDS + " 个");
        }
        return List.copyOf(new LinkedHashSet<>(tokens));
    }

    private static int validatePage(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < 1) {
            throw invalidRequest("page 必须大于等于 1");
        }
        return page;
    }

    private static int validatePageSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalidRequest("size 必须为 1 至 " + MAX_PAGE_SIZE);
        }
        return size;
    }

    // ---------- 归属与查错 ----------

    private WatchlistGroup requireActive(long userId, long groupId) {
        return groups.findActive(userId, groupId)
                .orElseThrow(() -> new WatchlistException(
                        WatchlistErrorCode.RESOURCE_NOT_FOUND, "分组不存在"));
    }

    private WatchlistGroup requireTargetGroup(long userId, long groupId) {
        return groups.findActive(userId, groupId)
                .orElseThrow(() -> new WatchlistException(
                        WatchlistErrorCode.GROUP_NOT_FOUND, "目标分组不存在"));
    }

    private SecurityIdentity requireSecurity(String securityId) {
        return securities.resolve(securityId)
                .orElseThrow(() -> new WatchlistException(
                        WatchlistErrorCode.RESOURCE_NOT_FOUND,
                        "证券不存在或不在证券主数据中：" + securityId));
    }

    /** 写入与回读用同一个 {@code Clock} 的时区，因此墙上时间无损往返（见 {@link WatchlistItem}）。 */
    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private static WatchlistException invalidRequest(String message) {
        return new WatchlistException(WatchlistErrorCode.INVALID_REQUEST, message);
    }

    private static WatchlistException staleItemSet() {
        return new WatchlistException(
                WatchlistErrorCode.VERSION_CONFLICT, "组内自选项已变化，请刷新后重试");
    }

    private static WatchlistException itemVersionConflict() {
        return new WatchlistException(
                WatchlistErrorCode.VERSION_CONFLICT, "自选项已被其他会话修改，请刷新后重试");
    }
}
