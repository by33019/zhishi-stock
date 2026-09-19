package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketBreadthQueryServiceTest {

  private static final OffsetDateTime LATEST_TIME =
      OffsetDateTime.of(2026, 9, 11, 14, 30, 0, 0, ZoneOffset.ofHours(8));

  private static final OffsetDateTime EARLIER_TIME =
      OffsetDateTime.of(2026, 9, 11, 10, 30, 0, 0, ZoneOffset.ofHours(8));

  @Test
  void returnsBreadthFromTheLiveSnapshot() {
    var snapshot = snapshot("live-v1", LATEST_TIME, new MarketOverview.BreadthData(10, 20, 3, 1, 2, 4));

    var breadth = service(Optional.of(snapshot), Optional.empty()).getBreadth("CN", null);

    assertThat(breadth.marketCode()).isEqualTo("CN");
    assertThat(breadth.riseCount()).isEqualTo(10);
    assertThat(breadth.fallCount()).isEqualTo(20);
    assertThat(breadth.flatCount()).isEqualTo(3);
    assertThat(breadth.suspendedCount()).isEqualTo(1);
    assertThat(breadth.limitUpCount()).isEqualTo(2);
    assertThat(breadth.limitDownCount()).isEqualTo(4);
    assertThat(breadth.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
    assertThat(breadth.snapshotVersion()).isEqualTo("live-v1");
  }

  @Test
  void derivesTotalCountAndKeepsLimitCountsAsSubsets() {
    var snapshot = snapshot("live-v1", LATEST_TIME, new MarketOverview.BreadthData(10, 20, 3, 1, 2, 4));

    var breadth = service(Optional.of(snapshot), Optional.empty()).getBreadth("CN", null);

    assertThat(breadth.totalCount()).isEqualTo(34);
    assertThat(breadth.limitUpCount()).isLessThanOrEqualTo(breadth.riseCount());
    assertThat(breadth.limitDownCount()).isLessThanOrEqualTo(breadth.fallCount());
  }

  @Test
  void fallsBackToArchivedSnapshotAndMarksStale() {
    var archived = snapshot("archive-v1", LATEST_TIME, new MarketOverview.BreadthData(1, 1, 0, 0, 0, 0));

    var breadth = service(Optional.empty(), Optional.of(archived)).getBreadth("CN", null);

    assertThat(breadth.snapshotVersion()).isEqualTo("archive-v1");
    assertThat(breadth.dataStatus()).isEqualTo(MarketOverview.DataStatus.STALE);
  }

  @Test
  void snapshotTimePinsToTheArchivedBatchAtOrBeforeThatMoment() {
    var live = snapshot("live-v1", LATEST_TIME, new MarketOverview.BreadthData(10, 10, 0, 0, 0, 0));
    var earlier = snapshot("earlier-v1", EARLIER_TIME, new MarketOverview.BreadthData(7, 7, 0, 0, 0, 0));

    var breadth = service(Optional.of(live), Optional.of(earlier))
        .getBreadth("CN", EARLIER_TIME);

    assertThat(breadth.snapshotVersion()).isEqualTo("earlier-v1");
    assertThat(breadth.riseCount()).isEqualTo(7);
    assertThat(breadth.dataTime()).isEqualTo(EARLIER_TIME);
    assertThat(breadth.dataStatus()).isEqualTo(MarketOverview.DataStatus.STALE);
  }

  @Test
  void snapshotTimeStillUsesLiveSnapshotWhenItIsNotNewerThanRequestedMoment() {
    var live = snapshot("live-v1", LATEST_TIME, new MarketOverview.BreadthData(10, 10, 0, 0, 0, 0));

    var breadth = service(Optional.of(live), Optional.empty())
        .getBreadth("CN", LATEST_TIME.plusMinutes(1));

    assertThat(breadth.snapshotVersion()).isEqualTo("live-v1");
    assertThat(breadth.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
  }

  @Test
  void throwsMarketDataUnavailableWhenNeitherSourceHasASnapshot() {
    assertThatThrownBy(() -> service(Optional.empty(), Optional.empty()).getBreadth("CN", null))
        .isInstanceOf(MarketDataUnavailableException.class)
        .hasMessageContaining("CN");
  }

  @Test
  void throwsMarketDataUnavailableWhenArchiveHasNoBatchBeforeRequestedMoment() {
    var live = snapshot("live-v1", LATEST_TIME, new MarketOverview.BreadthData(10, 10, 0, 0, 0, 0));

    assertThatThrownBy(() -> service(Optional.of(live), Optional.empty())
        .getBreadth("CN", EARLIER_TIME))
        .isInstanceOf(MarketDataUnavailableException.class);
  }

  private static MarketBreadthQueryService service(
      Optional<MarketOverview> live, Optional<MarketOverview> archived) {
    MarketOverviewStore store = market -> live;
    MarketOverviewArchive archive = new MarketOverviewArchive() {
      @Override
      public Optional<MarketOverview> findLatest(String marketCode) {
        return archived;
      }

      @Override
      public Optional<MarketOverview> findAt(String marketCode, OffsetDateTime snapshotTime) {
        return archived;
      }
    };
    return new MarketBreadthQueryService(new MarketOverviewQueryService(store, archive));
  }

  private static Optional<MarketOverview> archivedUnreachable() {
    return Optional.empty();
  }

  private static MarketOverview snapshot(
      String version, OffsetDateTime dataTime, MarketOverview.BreadthData breadth) {
    return new MarketOverview(
        "CN",
        MarketSessionStatus.TRADING,
        LocalDate.of(2026, 9, 11),
        dataTime,
        MarketOverview.DataStatus.REALTIME,
        List.of(),
        breadth,
        new MarketOverview.TurnoverData("0", "0", List.of()),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        dataTime,
        version);
  }
}
