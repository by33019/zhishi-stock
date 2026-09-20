package cn.zhishi.stock.system.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * WAT-06~WAT-12 的用例层测试。
 *
 * <p>{@code CLOCK} 的时区是 {@code Asia/Shanghai}：自选项的 {@code createdAt} 由应用按
 * {@code Clock} 的时区写入、也按同一时区回读，因此这里断言的是"14:30+08:00"而不是"06:30Z"。
 */
class WatchlistItemServiceTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long GROUP_ID = 7_000_000_000_001L;
  private static final long OTHER_GROUP_ID = 7_000_000_000_002L;
  private static final long ITEM_ID = 8_000_000_000_001L;
  private static final long OTHER_ITEM_ID = 8_000_000_000_002L;
  private static final long STORAGE_ID = 600_519L;
  private static final String SECURITY_ID = "sim-600519";

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-20T06:30:00Z"), ZoneId.of("Asia/Shanghai"));
  /** 库里的墙上时间（北京时间 14:30），回读后应当等于 {@code OffsetDateTime.now(CLOCK)}。 */
  private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 9, 20, 14, 30);

  private final WatchlistItemRepository items = mock(WatchlistItemRepository.class);
  private final WatchlistGroupRepository groups = mock(WatchlistGroupRepository.class);
  private final SecurityIdentityProvider securities = mock(SecurityIdentityProvider.class);
  private final QuoteSnapshotBatchProvider quotes = mock(QuoteSnapshotBatchProvider.class);
  private final AtomicLong ids = new AtomicLong(8_000_000_000_000L);
  private final WatchlistItemService service =
      new WatchlistItemService(items, groups, securities, quotes, ids::incrementAndGet, CLOCK);

  // ---------- WAT-06 ----------

  @Test
  void wat06ReturnsEntriesWithSecurityButWithoutQuoteUnlessAsked() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID)).thenReturn(List.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 0)));
    when(securities.findByStorageIds(any()))
        .thenReturn(Map.of(STORAGE_ID, identity()));

    PageData<WatchlistEntry> page = service.listItems(USER_ID, GROUP_ID, false, null, null);

    assertThat(page.items()).hasSize(1);
    WatchlistEntry entry = page.items().get(0);
    assertThat(entry.itemId()).isEqualTo(ITEM_ID);
    assertThat(entry.groupId()).isEqualTo(GROUP_ID);
    assertThat(entry.securityId()).isEqualTo(STORAGE_ID);
    assertThat(entry.sortNo()).isZero();
    assertThat(entry.version()).isZero();
    assertThat(entry.createdAt()).isEqualTo(OffsetDateTime.now(CLOCK));
    assertThat(entry.security()).isEqualTo(summary());
    assertThat(entry.quote()).isNull();
    assertThat(entry.latestNewsCount()).isNull();
    verify(quotes, never()).fetchBatch(anyString());
  }

  @Test
  void wat06AttachesTheQuoteOfTheSameBatchWhenAsked() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID)).thenReturn(List.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 0)));
    when(securities.findByStorageIds(any()))
        .thenReturn(Map.of(STORAGE_ID, identity()));
    when(quotes.fetchBatch("CN")).thenReturn(List.of(quote()));

    WatchlistEntry entry = service.listItems(USER_ID, GROUP_ID, true, null, null).items().get(0);

    assertThat(entry.quote()).isEqualTo(quote());
    verify(quotes).fetchBatch("CN");
  }

  @Test
  void wat06KeepsAnEntryWhoseSecurityIsMissingFromTheMasterData() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID)).thenReturn(List.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 0)));
    when(securities.findByStorageIds(any())).thenReturn(Map.of());
    when(quotes.fetchBatch("CN")).thenReturn(List.of());

    List<WatchlistEntry> entries = service.entriesOf(USER_ID, GROUP_ID, true);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).security()).isNull();
    assertThat(entries.get(0).quote()).isNull();
  }

  @Test
  void wat06PagesInMemoryAndRejectsOutOfRangeArguments() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID))
        .thenReturn(List.of(
            item(1L, GROUP_ID, STORAGE_ID, 0, 0),
            item(2L, GROUP_ID, STORAGE_ID, 1, 0),
            item(3L, GROUP_ID, STORAGE_ID, 2, 0)));
    when(securities.findByStorageIds(any())).thenReturn(Map.of());

    PageData<WatchlistEntry> page = service.listItems(USER_ID, GROUP_ID, false, 2, 2);

    assertThat(page.items()).extracting(WatchlistEntry::itemId).containsExactly(3L);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.totalPages()).isEqualTo(2);
    assertThat(page.hasNext()).isFalse();

    for (Object[] invalid : List.of(new Object[] {0, 20}, new Object[] {1, 0}, new Object[] {1, 101})) {
      WatchlistException exception = catchThrowableOfType(
          () -> service.listItems(USER_ID, GROUP_ID, false, (Integer) invalid[0], (Integer) invalid[1]),
          WatchlistException.class);
      assertThat(exception).describedAs("page/size = %s/%s 应当被拒绝", invalid[0], invalid[1])
          .isNotNull();
      assertThat(exception.code()).isEqualTo(WatchlistErrorCode.INVALID_REQUEST);
    }
  }

  @Test
  void wat06TreatsAGroupOfAnotherUserAsMissing() {
    when(groups.findActive(USER_ID, GROUP_ID)).thenReturn(Optional.empty());

    WatchlistException exception = catchThrowableOfType(
        () -> service.listItems(USER_ID, GROUP_ID, false, null, null), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.RESOURCE_NOT_FOUND);
    verify(items, never()).findByGroup(anyLong(), anyLong());
  }

  // ---------- WAT-07 ----------

  @Test
  void wat07AppendsAtTheEndAndReturnsTheStoredEntry() {
    activeGroup();
    when(securities.resolve(SECURITY_ID)).thenReturn(Optional.of(identity()));
    when(items.nextSortNo(USER_ID, GROUP_ID)).thenReturn(3);

    WatchlistEntry entry = service.addItem(USER_ID, GROUP_ID, SECURITY_ID);

    assertThat(entry.itemId()).isEqualTo(8_000_000_000_001L);
    assertThat(entry.groupId()).isEqualTo(GROUP_ID);
    assertThat(entry.securityId()).isEqualTo(STORAGE_ID);
    assertThat(entry.sortNo()).isEqualTo(3);
    assertThat(entry.version()).isZero();
    assertThat(entry.createdAt()).isEqualTo(OffsetDateTime.now(CLOCK));
    assertThat(entry.security()).isEqualTo(summary());
    verify(items).insert(new WatchlistItem(
        8_000_000_000_001L, USER_ID, GROUP_ID, STORAGE_ID, 3, 0, LocalDateTime.now(CLOCK)));
  }

  @Test
  void wat07ReturnsTheExistingItemWhenTheSecurityIsAlreadyInTheGroup() {
    activeGroup();
    when(securities.resolve(SECURITY_ID)).thenReturn(Optional.of(identity()));
    when(items.nextSortNo(USER_ID, GROUP_ID)).thenReturn(1);
    doThrow(new DuplicateKeyException("uk_watchlist_item_group_security"))
        .when(items)
        .insert(any());
    when(items.findBySecurity(USER_ID, GROUP_ID, STORAGE_ID))
        .thenReturn(Optional.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 0)));

    WatchlistEntry entry = service.addItem(USER_ID, GROUP_ID, SECURITY_ID);

    assertThat(entry.itemId()).isEqualTo(ITEM_ID);
    assertThat(entry.sortNo()).isZero();
    assertThat(entry.security()).isEqualTo(summary());
  }

  @Test
  void wat07RejectsASecurityThatIsNotInTheMasterData() {
    activeGroup();
    when(securities.resolve("sim-999999")).thenReturn(Optional.empty());

    WatchlistException exception = catchThrowableOfType(
        () -> service.addItem(USER_ID, GROUP_ID, "sim-999999"), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.RESOURCE_NOT_FOUND);
    verify(items, never()).insert(any());
  }

  @Test
  void wat07RejectsAGroupOfAnotherUser() {
    when(groups.findActive(USER_ID, GROUP_ID)).thenReturn(Optional.empty());

    WatchlistException exception = catchThrowableOfType(
        () -> service.addItem(USER_ID, GROUP_ID, SECURITY_ID), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.RESOURCE_NOT_FOUND);
    verify(securities, never()).resolve(anyString());
  }

  // ---------- WAT-08 ----------

  @Test
  void wat08ReportsWhetherARowWasActuallyRemoved() {
    activeGroup();
    when(items.delete(USER_ID, GROUP_ID, ITEM_ID)).thenReturn(true, false);

    assertThat(service.removeItem(USER_ID, GROUP_ID, ITEM_ID)).isTrue();
    assertThat(service.removeItem(USER_ID, GROUP_ID, ITEM_ID)).isFalse();
  }

  @Test
  void wat08ChecksTheGroupBeforeDeleting() {
    when(groups.findActive(USER_ID, GROUP_ID)).thenReturn(Optional.empty());

    WatchlistException exception = catchThrowableOfType(
        () -> service.removeItem(USER_ID, GROUP_ID, ITEM_ID), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.RESOURCE_NOT_FOUND);
    verify(items, never()).delete(anyLong(), anyLong(), anyLong());
  }

  // ---------- WAT-09 ----------

  @Test
  void wat09MovesTheItemAndReturnsTheReloadedRow() {
    activeGroup();
    when(groups.findActive(USER_ID, OTHER_GROUP_ID)).thenReturn(Optional.of(group(OTHER_GROUP_ID, "长期关注", 1, false, 0, 0)));
    when(items.find(USER_ID, GROUP_ID, ITEM_ID))
        .thenReturn(Optional.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 3)));
    when(items.findBySecurity(USER_ID, OTHER_GROUP_ID, STORAGE_ID)).thenReturn(Optional.empty());
    when(items.nextSortNo(USER_ID, OTHER_GROUP_ID)).thenReturn(7);
    when(items.moveToGroup(USER_ID, GROUP_ID, ITEM_ID, OTHER_GROUP_ID, 7, 3)).thenReturn(true);
    when(items.find(USER_ID, OTHER_GROUP_ID, ITEM_ID))
        .thenReturn(Optional.of(item(ITEM_ID, OTHER_GROUP_ID, STORAGE_ID, 7, 4)));

    MovedItem moved = service.moveItem(USER_ID, GROUP_ID, ITEM_ID, OTHER_GROUP_ID, 3);

    assertThat(moved.merged()).isFalse();
    assertThat(moved.item().groupId()).isEqualTo(OTHER_GROUP_ID);
    assertThat(moved.item().sortNo()).isEqualTo(7);
    assertThat(moved.item().version()).isEqualTo(4);
    verify(items, never()).deleteIfVersion(anyLong(), anyLong(), anyLong(), anyInt());
  }

  @Test
  void wat09MergesIntoTheExistingItemAndReturnsTheSurvivor() {
    activeGroup();
    when(groups.findActive(USER_ID, OTHER_GROUP_ID)).thenReturn(Optional.of(group(OTHER_GROUP_ID, "长期关注", 1, false, 0, 0)));
    when(items.find(USER_ID, GROUP_ID, ITEM_ID))
        .thenReturn(Optional.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 3)));
    when(items.findBySecurity(USER_ID, OTHER_GROUP_ID, STORAGE_ID))
        .thenReturn(Optional.of(item(OTHER_ITEM_ID, OTHER_GROUP_ID, STORAGE_ID, 5, 2)));
    when(items.deleteIfVersion(USER_ID, GROUP_ID, ITEM_ID, 3)).thenReturn(true);

    MovedItem moved = service.moveItem(USER_ID, GROUP_ID, ITEM_ID, OTHER_GROUP_ID, 3);

    assertThat(moved.merged()).isTrue();
    assertThat(moved.item().itemId()).isEqualTo(OTHER_ITEM_ID);
    assertThat(moved.item().sortNo()).isEqualTo(5);
    assertThat(moved.item().version()).isEqualTo(2);
    verify(items, never()).moveToGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
  }

  @Test
  void wat09ReportsVersionConflictWhenTheConditionalWriteMatchesNoRow() {
    activeGroup();
    when(groups.findActive(USER_ID, OTHER_GROUP_ID)).thenReturn(Optional.of(group(OTHER_GROUP_ID, "长期关注", 1, false, 0, 0)));
    when(items.find(USER_ID, GROUP_ID, ITEM_ID))
        .thenReturn(Optional.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 3)));
    when(items.findBySecurity(USER_ID, OTHER_GROUP_ID, STORAGE_ID)).thenReturn(Optional.empty());
    when(items.nextSortNo(USER_ID, OTHER_GROUP_ID)).thenReturn(0);
    when(items.moveToGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
        .thenReturn(false);

    WatchlistException exception = catchThrowableOfType(
        () -> service.moveItem(USER_ID, GROUP_ID, ITEM_ID, OTHER_GROUP_ID, 1), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.VERSION_CONFLICT);
  }

  @Test
  void wat09ReportsVersionConflictWhenTheMergeDeleteMatchesNoRow() {
    activeGroup();
    when(groups.findActive(USER_ID, OTHER_GROUP_ID)).thenReturn(Optional.of(group(OTHER_GROUP_ID, "长期关注", 1, false, 0, 0)));
    when(items.find(USER_ID, GROUP_ID, ITEM_ID))
        .thenReturn(Optional.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 3)));
    when(items.findBySecurity(USER_ID, OTHER_GROUP_ID, STORAGE_ID))
        .thenReturn(Optional.of(item(OTHER_ITEM_ID, OTHER_GROUP_ID, STORAGE_ID, 5, 2)));
    when(items.deleteIfVersion(anyLong(), anyLong(), anyLong(), anyInt())).thenReturn(false);

    WatchlistException exception = catchThrowableOfType(
        () -> service.moveItem(USER_ID, GROUP_ID, ITEM_ID, OTHER_GROUP_ID, 1), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.VERSION_CONFLICT);
  }

  @Test
  void wat09RejectsTheSourceGroupAsItsOwnTarget() {
    activeGroup();
    when(items.find(USER_ID, GROUP_ID, ITEM_ID))
        .thenReturn(Optional.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 3)));

    WatchlistException exception = catchThrowableOfType(
        () -> service.moveItem(USER_ID, GROUP_ID, ITEM_ID, GROUP_ID, 3), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.TARGET_GROUP_CONFLICT);
    verify(items, never()).moveToGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
  }

  @Test
  void wat09RejectsATargetThatIsNotTheUsersActiveGroup() {
    activeGroup();
    when(items.find(USER_ID, GROUP_ID, ITEM_ID))
        .thenReturn(Optional.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 3)));
    when(groups.findActive(USER_ID, OTHER_GROUP_ID)).thenReturn(Optional.empty());

    WatchlistException exception = catchThrowableOfType(
        () -> service.moveItem(USER_ID, GROUP_ID, ITEM_ID, OTHER_GROUP_ID, 3), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.GROUP_NOT_FOUND);
  }

  @Test
  void wat09TreatsAnItemOfAnotherUserAsMissing() {
    activeGroup();
    when(items.find(USER_ID, GROUP_ID, ITEM_ID)).thenReturn(Optional.empty());

    WatchlistException exception = catchThrowableOfType(
        () -> service.moveItem(USER_ID, GROUP_ID, ITEM_ID, OTHER_GROUP_ID, 3), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.RESOURCE_NOT_FOUND);
  }

  // ---------- WAT-10 ----------

  @Test
  void wat10RewritesSortNumbersAndBumpsEveryVersion() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID))
        .thenReturn(List.of(
            item(1L, GROUP_ID, STORAGE_ID, 0, 4),
            item(2L, GROUP_ID, STORAGE_ID, 1, 0),
            item(3L, GROUP_ID, STORAGE_ID, 2, 7)),
            List.of(
                item(3L, GROUP_ID, STORAGE_ID, 0, 8),
                item(1L, GROUP_ID, STORAGE_ID, 1, 5),
                item(2L, GROUP_ID, STORAGE_ID, 2, 1)));
    when(items.updateSortNo(anyLong(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(true);

    List<WatchlistItem> reordered = service.reorderItems(USER_ID, GROUP_ID, List.of(3L, 1L, 2L));

    verify(items).updateSortNo(USER_ID, GROUP_ID, 3L, 0, 7);
    verify(items).updateSortNo(USER_ID, GROUP_ID, 1L, 1, 4);
    verify(items).updateSortNo(USER_ID, GROUP_ID, 2L, 2, 0);
    assertThat(reordered).extracting(WatchlistItem::itemId).containsExactly(3L, 1L, 2L);
  }

  @Test
  void wat10RejectsDuplicatesAsABadRequest() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID))
        .thenReturn(List.of(item(1L, GROUP_ID, STORAGE_ID, 0, 0), item(2L, GROUP_ID, STORAGE_ID, 1, 0)));

    WatchlistException exception = catchThrowableOfType(
        () -> service.reorderItems(USER_ID, GROUP_ID, List.of(1L, 1L)), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.INVALID_REQUEST);
  }

  @Test
  void wat10RejectsAMissingNullOrStaleSetAsAConflict() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID))
        .thenReturn(List.of(item(1L, GROUP_ID, STORAGE_ID, 0, 0), item(2L, GROUP_ID, STORAGE_ID, 1, 0)));

    for (List<Long> requested : List.of(List.of(1L), List.of(1L, 2L, 3L), List.of(1L, 9L))) {
      WatchlistException exception = catchThrowableOfType(
          () -> service.reorderItems(USER_ID, GROUP_ID, requested), WatchlistException.class);
      assertThat(exception).describedAs("itemIds=%s 应当被判为过期", requested).isNotNull();
      assertThat(exception.code()).isEqualTo(WatchlistErrorCode.VERSION_CONFLICT);
    }
    WatchlistException nullList = catchThrowableOfType(
        () -> service.reorderItems(USER_ID, GROUP_ID, null), WatchlistException.class);
    assertThat(nullList).isNotNull();
    assertThat(nullList.code()).isEqualTo(WatchlistErrorCode.INVALID_REQUEST);
    verify(items, never()).updateSortNo(anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
  }

  @Test
  void wat10AcceptsAnEmptySetForAnEmptyGroup() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID)).thenReturn(List.of(), List.of());

    assertThat(service.reorderItems(USER_ID, GROUP_ID, List.of())).isEmpty();
    verify(items, never()).updateSortNo(anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
  }

  @Test
  void wat10StopsAtTheFirstRowThatNoLongerMatchesTheVersionItRead() {
    activeGroup();
    when(items.findByGroup(USER_ID, GROUP_ID))
        .thenReturn(List.of(item(1L, GROUP_ID, STORAGE_ID, 0, 0), item(2L, GROUP_ID, STORAGE_ID, 1, 0)));
    when(items.updateSortNo(USER_ID, GROUP_ID, 2L, 0, 0)).thenReturn(true);
    when(items.updateSortNo(USER_ID, GROUP_ID, 1L, 1, 0)).thenReturn(false);

    WatchlistException exception = catchThrowableOfType(
        () -> service.reorderItems(USER_ID, GROUP_ID, List.of(2L, 1L)), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.VERSION_CONFLICT);
  }

  // ---------- WAT-11 ----------

  @Test
  void wat11ReturnsEveryGroupAndItsItemsWhenNoGroupIsGiven() {
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(
            group(GROUP_ID, "默认分组", 0, true, 0, 1),
            group(OTHER_GROUP_ID, "长期关注", 1, false, 0, 1)));
    when(items.findByUser(USER_ID))
        .thenReturn(List.of(
            item(2L, OTHER_GROUP_ID, STORAGE_ID, 0, 0),
            item(1L, GROUP_ID, STORAGE_ID, 0, 0)));
    when(securities.findByStorageIds(any())).thenReturn(Map.of(STORAGE_ID, identity()));
    when(quotes.fetchBatch("CN")).thenReturn(List.of(quote()));

    WatchlistOverview overview = service.overview(USER_ID, null);

    assertThat(overview.groups()).hasSize(2);
    // 分组顺序决定条目顺序：默认分组（sortNo 0）在前，即使 itemId 更大
    assertThat(overview.entries()).extracting(WatchlistEntry::groupId)
        .containsExactly(GROUP_ID, OTHER_GROUP_ID);
    assertThat(overview.snapshotVersion()).isEqualTo("sim-20260920");
    assertThat(overview.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
    assertThat(overview.dataTime()).isEqualTo(OffsetDateTime.parse("2026-09-20T07:00:00Z"));
    assertThat(overview.limitations())
        .containsExactly("最新资讯数尚未实现（资讯 Provider 见 M3-04），latestNewsCount 恒为 null");
  }

  @Test
  void wat11FiltersToOneGroupWhenGiven() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "默认分组", 0, true, 0, 1)));
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(group(GROUP_ID, "默认分组", 0, true, 0, 1)));
    when(items.findByGroup(USER_ID, GROUP_ID)).thenReturn(List.of(item(1L, GROUP_ID, STORAGE_ID, 0, 0)));
    when(securities.findByStorageIds(any())).thenReturn(Map.of(STORAGE_ID, identity()));
    when(quotes.fetchBatch("CN")).thenReturn(List.of(quote()));

    WatchlistOverview overview = service.overview(USER_ID, GROUP_ID);

    assertThat(overview.entries()).extracting(WatchlistEntry::groupId).containsExactly(GROUP_ID);
    verify(items, never()).findByUser(anyLong());
  }

  @Test
  void wat11KeepsEntriesAndExplainsWhatIsMissing() {
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(group(GROUP_ID, "默认分组", 0, true, 0, 2)));
    when(items.findByUser(USER_ID))
        .thenReturn(List.of(
            item(1L, GROUP_ID, STORAGE_ID, 0, 0),
            item(2L, GROUP_ID, 999_999L, 1, 0)));
    when(securities.findByStorageIds(any())).thenReturn(Map.of(STORAGE_ID, identity()));
    when(quotes.fetchBatch("CN")).thenReturn(List.of());

    WatchlistOverview overview = service.overview(USER_ID, null);

    assertThat(overview.entries()).hasSize(2);
    assertThat(overview.limitations()).contains(
        "1 只自选证券不在证券主数据中，仅返回自选关系",
        "1 只自选证券当前没有行情快照，已保留自选关系");
  }

  @Test
  void wat11DoesNotAskForABatchWhenThereIsNothingToQuote() {
    when(groups.findActiveByUser(USER_ID)).thenReturn(List.of());
    when(items.findByUser(USER_ID)).thenReturn(List.of());

    WatchlistOverview overview = service.overview(USER_ID, null);

    assertThat(overview.entries()).isEmpty();
    assertThat(overview.snapshotVersion()).isEmpty();
    assertThat(overview.dataStatus()).isEqualTo(MarketOverview.DataStatus.UNAVAILABLE);
    assertThat(overview.dataTime()).isNull();
    verify(quotes, never()).fetchBatch(anyString());
    verify(securities, never()).findByStorageIds(any());
  }

  @Test
  void wat11IgnoresItemsOfSoftDeletedGroups() {
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(group(GROUP_ID, "默认分组", 0, true, 0, 1)));
    when(items.findByUser(USER_ID))
        .thenReturn(List.of(
            item(1L, GROUP_ID, STORAGE_ID, 0, 0),
            item(2L, OTHER_GROUP_ID, STORAGE_ID, 0, 0)));
    when(securities.findByStorageIds(any())).thenReturn(Map.of(STORAGE_ID, identity()));
    when(quotes.fetchBatch("CN")).thenReturn(List.of(quote()));

    assertThat(service.overview(USER_ID, null).entries())
        .extracting(WatchlistEntry::itemId)
        .containsExactly(1L);
  }

  // ---------- WAT-12 ----------

  @Test
  void wat12ReturnsOnlyTheSecuritiesThatAreInTheWatchlist() {
    when(securities.resolveAll(any()))
        .thenReturn(Map.of(SECURITY_ID, identity()));
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(group(GROUP_ID, "默认分组", 0, true, 0, 1)));
    when(items.findByUser(USER_ID)).thenReturn(List.of(item(ITEM_ID, GROUP_ID, STORAGE_ID, 0, 0)));

    Map<String, WatchlistMembership> membership =
        service.membership(USER_ID, SECURITY_ID + ",sim-000001");

    assertThat(membership).containsOnlyKeys(SECURITY_ID);
    assertThat(membership.get(SECURITY_ID))
        .isEqualTo(new WatchlistMembership(GROUP_ID, "默认分组", ITEM_ID));
  }

  @Test
  void wat12PicksTheFirstGroupInGroupOrder() {
    when(securities.resolveAll(any())).thenReturn(Map.of(SECURITY_ID, identity()));
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(
            group(GROUP_ID, "默认分组", 0, true, 0, 1),
            group(OTHER_GROUP_ID, "长期关注", 1, false, 0, 1)));
    when(items.findByUser(USER_ID))
        .thenReturn(List.of(
            item(2L, OTHER_GROUP_ID, STORAGE_ID, 0, 0),
            item(1L, GROUP_ID, STORAGE_ID, 0, 0)));

    assertThat(service.membership(USER_ID, SECURITY_ID).get(SECURITY_ID))
        .isEqualTo(new WatchlistMembership(GROUP_ID, "默认分组", 1L));
  }

  @Test
  void wat12RejectsAnEmptyOrOversizedIdList() {
    StringBuilder tooMany = new StringBuilder();
    for (int i = 0; i < 51; i++) {
      tooMany.append(i == 0 ? "" : ",").append("sim-").append(600_000 + i);
    }

    for (String requested : List.of("", "   ", ",,,", tooMany.toString())) {
      WatchlistException exception = catchThrowableOfType(
          () -> service.membership(USER_ID, requested), WatchlistException.class);
      assertThat(exception).describedAs("securityIds=%s 应当被拒绝", requested).isNotNull();
      assertThat(exception.code()).isEqualTo(WatchlistErrorCode.INVALID_REQUEST);
    }
    WatchlistException nullList = catchThrowableOfType(
        () -> service.membership(USER_ID, null), WatchlistException.class);
    assertThat(nullList).isNotNull();
    assertThat(nullList.code()).isEqualTo(WatchlistErrorCode.INVALID_REQUEST);
    verify(securities, never()).resolveAll(any());
  }

  @Test
  void wat12ReturnsAnEmptyMapWithoutTouchingStorageWhenNothingResolves() {
    when(securities.resolveAll(any())).thenReturn(Map.of());

    assertThat(service.membership(USER_ID, "sim-999999")).isEmpty();
    verify(items, never()).findByUser(anyLong());
  }

  // ---------- 小工具 ----------

  private void activeGroup() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "默认分组", 0, true, 0, 0)));
  }

  private static WatchlistGroup group(
      long groupId, String name, int sortNo, boolean isDefault, int version, int itemCount) {
    return new WatchlistGroup(groupId, USER_ID, name, sortNo, isDefault, version, itemCount);
  }

  private static WatchlistItem item(
      long itemId, long groupId, long securityId, int sortNo, int version) {
    return new WatchlistItem(itemId, USER_ID, groupId, securityId, sortNo, version, STORED_AT);
  }

  private static SecurityIdentity identity() {
    return new SecurityIdentity(STORAGE_ID, summary());
  }

  private static SecuritySummary summary() {
    return new SecuritySummary(
        SECURITY_ID, "SH.600519", "600519", "模拟证券600519", "SH", "STOCK", "MAIN",
        "LISTED", false, false, 2, null, null);
  }

  private static QuoteSnapshot quote() {
    return new QuoteSnapshot(
        summary(), "10.00", "10.10", "10.50", "10.60", "9.90", "0.50", "0.05",
        "1000", "10500", "0.01",
        OffsetDateTime.parse("2026-09-20T07:00:00Z"),
        OffsetDateTime.parse("2026-09-20T07:00:05Z"),
        "sim-20260920", MarketOverview.DataStatus.REALTIME, 0);
  }
}
