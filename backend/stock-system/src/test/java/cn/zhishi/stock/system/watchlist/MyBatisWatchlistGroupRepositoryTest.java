package cn.zhishi.stock.system.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MyBatisWatchlistGroupRepositoryTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long GROUP_ID = 7_000_000_000_001L;
  private static final long TARGET_GROUP_ID = 7_000_000_000_002L;

  private final WatchlistGroupMapper mapper = mock(WatchlistGroupMapper.class);
  private final MyBatisWatchlistGroupRepository repository =
      new MyBatisWatchlistGroupRepository(mapper);

  @Test
  void mapsRowsToDomainRecords() {
    when(mapper.findActiveByUser(USER_ID))
        .thenReturn(List.of(new WatchlistGroupRow(GROUP_ID, USER_ID, "默认分组", 0, true, 3, 2)));

    assertThat(repository.findActiveByUser(USER_ID))
        .containsExactly(new WatchlistGroup(GROUP_ID, USER_ID, "默认分组", 0, true, 3, 2));
  }

  @Test
  void aMissingRowBecomesAnEmptyOptional() {
    when(mapper.findActive(USER_ID, GROUP_ID)).thenReturn(null);

    assertThat(repository.findActive(USER_ID, GROUP_ID)).isEmpty();
  }

  @Test
  void conditionalWritesReportWhetherExactlyOneRowMatched() {
    when(mapper.rename(USER_ID, GROUP_ID, "新名字", 3)).thenReturn(1);
    when(mapper.softDelete(USER_ID, GROUP_ID, 4)).thenReturn(0);

    assertThat(repository.rename(USER_ID, GROUP_ID, "新名字", 3)).isTrue();
    assertThat(repository.softDelete(USER_ID, GROUP_ID, 4)).isFalse();
  }

  @Test
  void insertForwardsTheDomainValuesAndLetsTheDatabaseOwnTimestampsAndVersion() {
    repository.insert(new WatchlistGroup(GROUP_ID, USER_ID, "我的自选", 5, false, 0, 0));

    InOrder inOrder = inOrder(mapper);
    inOrder.verify(mapper).insert(GROUP_ID, USER_ID, "我的自选", 5, false);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  void reorderWritesTheSubmittedOrderAsZeroBasedIndexes() {
    repository.reorder(USER_ID, List.of(30L, 10L, 20L));

    InOrder inOrder = inOrder(mapper);
    inOrder.verify(mapper).updateSortNo(USER_ID, 30L, 0);
    inOrder.verify(mapper).updateSortNo(USER_ID, 10L, 1);
    inOrder.verify(mapper).updateSortNo(USER_ID, 20L, 2);
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * 目标组已有同一只证券时，若不先合并掉重复项，第 2 步的 UPDATE 会撞上
   * {@code uk_watchlist_item_group_security(group_id, security_id)}。
   */
  @Test
  void movingItemsMergesTheTargetGroupsDuplicatesFirst() {
    when(mapper.moveItems(USER_ID, GROUP_ID, TARGET_GROUP_ID)).thenReturn(3);

    assertThat(repository.moveItems(USER_ID, GROUP_ID, TARGET_GROUP_ID)).isEqualTo(3);

    InOrder inOrder = inOrder(mapper);
    inOrder.verify(mapper).deleteItemsAlreadyInTarget(USER_ID, GROUP_ID, TARGET_GROUP_ID);
    inOrder.verify(mapper).moveItems(USER_ID, GROUP_ID, TARGET_GROUP_ID);
  }
}
