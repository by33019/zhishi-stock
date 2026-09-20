package cn.zhishi.stock.system.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MyBatisWatchlistItemRepositoryTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long GROUP_ID = 7_000_000_000_001L;
  private static final long TARGET_GROUP_ID = 7_000_000_000_002L;
  private static final long ITEM_ID = 8_000_000_000_001L;
  private static final long SECURITY_ID = 600_519L;
  /** 库里的墙上时间；这一层不做任何时区换算，原样交给用例层。 */
  private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 9, 20, 14, 30);

  private final WatchlistItemMapper mapper = mock(WatchlistItemMapper.class);
  private final MyBatisWatchlistItemRepository repository =
      new MyBatisWatchlistItemRepository(mapper);

  @Test
  void mapsRowsToDomainRecordsWithoutTouchingTheWallClockTimestamp() {
    when(mapper.findByGroup(USER_ID, GROUP_ID))
        .thenReturn(List.of(new WatchlistItemRow(
            ITEM_ID, USER_ID, GROUP_ID, SECURITY_ID, 0, 3, STORED_AT)));

    assertThat(repository.findByGroup(USER_ID, GROUP_ID))
        .containsExactly(new WatchlistItem(
            ITEM_ID, USER_ID, GROUP_ID, SECURITY_ID, 0, 3, STORED_AT));
  }

  @Test
  void aMissingRowBecomesAnEmptyOptional() {
    when(mapper.find(USER_ID, GROUP_ID, ITEM_ID)).thenReturn(null);
    when(mapper.findBySecurity(USER_ID, GROUP_ID, SECURITY_ID)).thenReturn(null);

    assertThat(repository.find(USER_ID, GROUP_ID, ITEM_ID)).isEmpty();
    assertThat(repository.findBySecurity(USER_ID, GROUP_ID, SECURITY_ID)).isEmpty();
  }

  @Test
  void conditionalWritesReportWhetherExactlyOneRowMatched() {
    when(mapper.deleteIfVersion(USER_ID, GROUP_ID, ITEM_ID, 3)).thenReturn(1);
    when(mapper.updateSortNo(USER_ID, GROUP_ID, ITEM_ID, 2, 3)).thenReturn(0);

    assertThat(repository.deleteIfVersion(USER_ID, GROUP_ID, ITEM_ID, 3)).isTrue();
    assertThat(repository.updateSortNo(USER_ID, GROUP_ID, ITEM_ID, 2, 3)).isFalse();
  }

  @Test
  void plainDeleteReportsWhetherARowWasActuallyRemoved() {
    when(mapper.delete(USER_ID, GROUP_ID, ITEM_ID)).thenReturn(0);

    assertThat(repository.delete(USER_ID, GROUP_ID, ITEM_ID)).isFalse();
  }

  /** {@code created_at} 由应用写入，仓储只负责把它原样传给 SQL。 */
  @Test
  void insertForwardsTheApplicationOwnedCreatedAtAndTheZeroVersion() {
    repository.insert(new WatchlistItem(
        ITEM_ID, USER_ID, GROUP_ID, SECURITY_ID, 4, 0, STORED_AT));

    InOrder inOrder = inOrder(mapper);
    inOrder.verify(mapper).insert(ITEM_ID, USER_ID, GROUP_ID, SECURITY_ID, 4, STORED_AT);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  void moveToGroupForwardsTheTargetGroupAndTheNewSortNumber() {
    when(mapper.moveToGroup(USER_ID, GROUP_ID, ITEM_ID, TARGET_GROUP_ID, 7, 3)).thenReturn(1);

    assertThat(repository.moveToGroup(USER_ID, GROUP_ID, ITEM_ID, TARGET_GROUP_ID, 7, 3))
        .isTrue();

    verify(mapper).moveToGroup(USER_ID, GROUP_ID, ITEM_ID, TARGET_GROUP_ID, 7, 3);
    verifyNoMoreInteractions(mapper);
  }
}
