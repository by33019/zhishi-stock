package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketOverviewQueryServiceTest {

  @Test
  void returnsCachedSnapshotBeforeArchive() {
    var cached = snapshot("cache-v1");
    var archived = snapshot("archive-v1");
    MarketOverviewStore store = market -> Optional.of(cached);
    MarketOverviewArchive archive = market -> Optional.of(archived);
    var service = new MarketOverviewQueryService(store, archive);

    var result = service.getOverview("CN");

    assertThat(result).isSameAs(cached);
  }

  @Test
  void fallsBackToLatestArchiveAndMarksSnapshotStale() {
    var archived = snapshot("archive-v1");
    MarketOverviewStore store = market -> Optional.empty();
    MarketOverviewArchive archive = market -> Optional.of(archived);

    var result = new MarketOverviewQueryService(store, archive).getOverview("CN");

    assertThat(result.snapshotVersion()).isEqualTo("archive-v1");
    assertThat(result.dataStatus()).isEqualTo(MarketOverview.DataStatus.STALE);
    assertThat(result.componentStatus())
        .containsEntry("indices", MarketOverview.DataStatus.STALE);
  }

  @Test
  void throwsDomainExceptionWhenNoSnapshotExists() {
    MarketOverviewStore store = market -> Optional.empty();
    MarketOverviewArchive archive = market -> Optional.empty();

    assertThatThrownBy(() -> new MarketOverviewQueryService(store, archive).getOverview("CN"))
        .isInstanceOf(MarketDataUnavailableException.class)
        .hasMessageContaining("CN");
  }

  @Test
  void mapsArchiveFailureToMarketUnavailable() {
    MarketOverviewStore store = market -> Optional.empty();
    MarketOverviewArchive archive = market -> {
      throw new IllegalStateException("database unavailable");
    };

    assertThatThrownBy(() -> new MarketOverviewQueryService(store, archive).getOverview("CN"))
        .isInstanceOf(MarketDataUnavailableException.class)
        .hasMessageContaining("CN")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  private static MarketOverview snapshot(String version) {
    var now = OffsetDateTime.of(2026, 9, 11, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    return new MarketOverview(
        "CN",
        MarketOverview.SessionStatus.TRADING,
        LocalDate.of(2026, 9, 11),
        now,
        MarketOverview.DataStatus.REALTIME,
        List.of(),
        new MarketOverview.BreadthData(1, 1, 0, 0, 0),
        new MarketOverview.TurnoverData("0", "0", List.of()),
        List.of(),
        List.of(),
        List.of(),
        Map.of("indices", MarketOverview.DataStatus.REALTIME),
        now,
        version);
  }
}
