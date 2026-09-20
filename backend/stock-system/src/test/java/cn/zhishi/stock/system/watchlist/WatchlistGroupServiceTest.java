package cn.zhishi.stock.system.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class WatchlistGroupServiceTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long GROUP_ID = 7_000_000_000_001L;
  private static final long OTHER_GROUP_ID = 7_000_000_000_002L;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-20T06:30:00Z"), ZoneId.of("Asia/Shanghai"));

  private final WatchlistGroupRepository groups = mock(WatchlistGroupRepository.class);
  private final AtomicLong ids = new AtomicLong(7_000_000_000_000L);
  private final WatchlistGroupService service =
      new WatchlistGroupService(groups, ids::incrementAndGet, CLOCK);

  // ---------- WAT-01 ----------

  @Test
  void wat01ReturnsTheRepositoryOrderAsIs() {
    List<WatchlistGroup> stored =
        List.of(group(1L, "默认分组", 0, true, 0, 3), group(2L, "我的自选", 1, false, 2, 0));
    when(groups.findActiveByUser(USER_ID)).thenReturn(stored);

    assertThat(service.list(USER_ID)).isEqualTo(stored);
  }

  // ---------- WAT-02 ----------

  @Test
  void wat02TrimsTheNameAndAppendsToTheEndOfTheOrder() {
    when(groups.nextSortNo(USER_ID)).thenReturn(4);

    CreatedGroup created = service.create(USER_ID, "  我的自选  ");

    assertThat(created.group().groupId()).isEqualTo(7_000_000_000_001L);
    assertThat(created.group().userId()).isEqualTo(USER_ID);
    assertThat(created.group().groupName()).isEqualTo("我的自选");
    assertThat(created.group().sortNo()).isEqualTo(4);
    assertThat(created.group().isDefault()).isFalse();
    assertThat(created.group().version()).isZero();
    assertThat(created.group().itemCount()).isZero();
    assertThat(created.createdAt()).isEqualTo(OffsetDateTime.now(CLOCK));
    verify(groups).insert(created.group());
  }

  @Test
  void wat02TranslatesAUniqueConstraintViolationIntoGroupNameExists() {
    when(groups.nextSortNo(USER_ID)).thenReturn(1);
    doThrow(new DuplicateKeyException("uk_watchlist_group_user_name"))
        .when(groups)
        .insert(any());

    WatchlistException exception =
        catchThrowableOfType(() -> service.create(USER_ID, "我的自选"), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.GROUP_NAME_EXISTS);
  }

  @Test
  void wat02RejectsAnInvalidNameBeforeTouchingTheRepository() {
    WatchlistException exception =
        catchThrowableOfType(() -> service.create(USER_ID, "   "), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.GROUP_NAME_INVALID);
    verify(groups, never()).insert(any());
  }

  // ---------- WAT-03 ----------

  @Test
  void wat03RenamesWithTheExpectedVersionAndReturnsTheReloadedGroup() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(
            Optional.of(group(GROUP_ID, "我的自选", 1, false, 3, 0)),
            Optional.of(group(GROUP_ID, "核心持仓", 1, false, 4, 0)));
    when(groups.rename(USER_ID, GROUP_ID, "核心持仓", 3)).thenReturn(true);

    WatchlistGroup renamed = service.rename(USER_ID, GROUP_ID, "  核心持仓 ", 3);

    assertThat(renamed.groupName()).isEqualTo("核心持仓");
    assertThat(renamed.version()).isEqualTo(4);
    verify(groups).rename(USER_ID, GROUP_ID, "核心持仓", 3);
  }

  @Test
  void wat03ReportsVersionConflictWhenTheConditionalUpdateMatchesNoRow() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 3, 0)));
    when(groups.rename(anyLong(), anyLong(), anyString(), anyInt())).thenReturn(false);

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.rename(USER_ID, GROUP_ID, "核心持仓", 2), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.VERSION_CONFLICT);
  }

  @Test
  void wat03AllowsRenamingTheDefaultGroupWithoutLosingTheDefaultFlag() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(
            Optional.of(group(GROUP_ID, "默认分组", 0, true, 0, 0)),
            Optional.of(group(GROUP_ID, "长期关注", 0, true, 1, 0)));
    when(groups.rename(USER_ID, GROUP_ID, "长期关注", 0)).thenReturn(true);

    WatchlistGroup renamed = service.rename(USER_ID, GROUP_ID, "长期关注", 0);

    assertThat(renamed.isDefault()).isTrue();
    assertThat(renamed.groupName()).isEqualTo("长期关注");
  }

  @Test
  void wat03TreatsAGroupOfAnotherUserAsMissing() {
    when(groups.findActive(USER_ID, GROUP_ID)).thenReturn(Optional.empty());

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.rename(USER_ID, GROUP_ID, "核心持仓", 0), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.RESOURCE_NOT_FOUND);
    verify(groups, never()).rename(anyLong(), anyLong(), anyString(), anyInt());
  }

  @Test
  void wat03TranslatesAUniqueConstraintViolationIntoGroupNameExists() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 3, 0)));
    when(groups.rename(USER_ID, GROUP_ID, "长期关注", 3))
        .thenThrow(new DuplicateKeyException("uk_watchlist_group_user_name"));

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.rename(USER_ID, GROUP_ID, "长期关注", 3), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.GROUP_NAME_EXISTS);
  }

  // ---------- WAT-04 ----------

  @Test
  void wat04RefusesToDeleteTheDefaultGroup() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "默认分组", 0, true, 0, 0)));

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.delete(USER_ID, GROUP_ID, 0, null), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.DEFAULT_GROUP_CANNOT_DELETE);
    verify(groups, never()).softDelete(anyLong(), anyLong(), anyInt());
  }

  @Test
  void wat04RequiresATargetGroupWhenTheGroupIsNotEmpty() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 0, 5)));

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.delete(USER_ID, GROUP_ID, 0, null), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.TARGET_GROUP_REQUIRED);
  }

  @Test
  void wat04RejectsTheSourceGroupAsItsOwnTarget() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 0, 5)));

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.delete(USER_ID, GROUP_ID, 0, GROUP_ID), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.TARGET_GROUP_CONFLICT);
  }

  @Test
  void wat04RejectsATargetThatIsNotTheUsersActiveGroup() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 0, 5)));
    when(groups.findActive(USER_ID, OTHER_GROUP_ID)).thenReturn(Optional.empty());

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.delete(USER_ID, GROUP_ID, 0, OTHER_GROUP_ID), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.GROUP_NOT_FOUND);
    verify(groups, never()).softDelete(anyLong(), anyLong(), anyInt());
  }

  @Test
  void wat04IgnoresTheTargetGroupWhenTheGroupIsAlreadyEmpty() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 2, 0)));
    when(groups.softDelete(USER_ID, GROUP_ID, 2)).thenReturn(true);

    DeleteResult result = service.delete(USER_ID, GROUP_ID, 2, OTHER_GROUP_ID);

    assertThat(result).isEqualTo(new DeleteResult(true, 0));
    verify(groups, never()).findActive(USER_ID, OTHER_GROUP_ID);
    verify(groups, never()).moveItems(anyLong(), anyLong(), anyLong());
  }

  @Test
  void wat04MovesItemsThenSoftDeletesAndReportsTheMovedCount() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 2, 5)));
    when(groups.findActive(USER_ID, OTHER_GROUP_ID))
        .thenReturn(Optional.of(group(OTHER_GROUP_ID, "长期关注", 2, false, 0, 1)));
    when(groups.softDelete(USER_ID, GROUP_ID, 2)).thenReturn(true);
    when(groups.moveItems(USER_ID, GROUP_ID, OTHER_GROUP_ID)).thenReturn(4);

    DeleteResult result = service.delete(USER_ID, GROUP_ID, 2, OTHER_GROUP_ID);

    assertThat(result).isEqualTo(new DeleteResult(true, 4));
  }

  @Test
  void wat04ReportsVersionConflictBeforeTouchingItems() {
    when(groups.findActive(USER_ID, GROUP_ID))
        .thenReturn(Optional.of(group(GROUP_ID, "我的自选", 1, false, 2, 5)));
    when(groups.findActive(USER_ID, OTHER_GROUP_ID))
        .thenReturn(Optional.of(group(OTHER_GROUP_ID, "长期关注", 2, false, 0, 1)));
    when(groups.softDelete(USER_ID, GROUP_ID, 1)).thenReturn(false);

    WatchlistException exception =
        catchThrowableOfType(
            () -> service.delete(USER_ID, GROUP_ID, 1, OTHER_GROUP_ID), WatchlistException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.code()).isEqualTo(WatchlistErrorCode.VERSION_CONFLICT);
    verify(groups, never()).moveItems(anyLong(), anyLong(), anyLong());
  }

  // ---------- WAT-05 ----------

  @Test
  void wat05ReordersWhenTheRequestIsExactlyTheUsersActiveGroups() {
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(
            List.of(group(1L, "A", 0, true, 0, 0), group(2L, "B", 1, false, 0, 0)),
            List.of(group(2L, "B", 0, false, 1, 0), group(1L, "A", 1, true, 1, 0)));

    List<WatchlistGroup> reordered = service.reorder(USER_ID, List.of(2L, 1L));

    assertThat(reordered).extracting(WatchlistGroup::groupId).containsExactly(2L, 1L);
    verify(groups).reorder(USER_ID, List.of(2L, 1L));
  }

  @Test
  void wat05RejectsIncompleteDuplicateOrForeignIdSets() {
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(group(1L, "A", 0, true, 0, 0), group(2L, "B", 1, false, 0, 0)));
    List<List<Long>> invalid =
        List.of(List.of(1L), List.of(1L, 2L, 3L), List.of(1L, 1L), List.of(1L, 3L), List.of());

    for (List<Long> requested : invalid) {
      WatchlistException exception =
          catchThrowableOfType(() -> service.reorder(USER_ID, requested), WatchlistException.class);
      assertThat(exception).describedAs("groupIds=%s 应当被拒绝", requested).isNotNull();
      assertThat(exception.code()).isEqualTo(WatchlistErrorCode.INVALID_REQUEST);
    }
    verify(groups, never()).reorder(anyLong(), any());
  }

  // ---------- 默认分组 ----------

  @Test
  void defaultGroupIsNotCreatedTwice() {
    WatchlistGroup existing = group(1L, WatchlistGroupService.DEFAULT_GROUP_NAME, 0, true, 0, 2);
    when(groups.findActiveByUser(USER_ID)).thenReturn(List.of(existing));

    assertThat(service.createDefaultGroup(USER_ID)).isEqualTo(existing);
    verify(groups, never()).insert(any());
  }

  @Test
  void defaultGroupIsCreatedAtSortZeroAndIsMarkedDefault() {
    when(groups.findActiveByUser(USER_ID))
        .thenReturn(List.of(), List.of(group(7_000_000_000_001L, "默认分组", 0, true, 0, 0)));

    WatchlistGroup created = service.createDefaultGroup(USER_ID);

    assertThat(created.isDefault()).isTrue();
    assertThat(created.groupName()).isEqualTo(WatchlistGroupService.DEFAULT_GROUP_NAME);
    assertThat(created.sortNo()).isZero();
    verify(groups)
        .insert(new WatchlistGroup(
            7_000_000_000_001L, USER_ID, WatchlistGroupService.DEFAULT_GROUP_NAME, 0, true, 0, 0));
  }

  private static WatchlistGroup group(
      long groupId, String name, int sortNo, boolean isDefault, int version, int itemCount) {
    return new WatchlistGroup(groupId, USER_ID, name, sortNo, isDefault, version, itemCount);
  }
}
