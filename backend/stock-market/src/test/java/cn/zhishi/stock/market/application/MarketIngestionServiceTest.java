package cn.zhishi.stock.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.QuoteProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarketIngestionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");

  @Test
  void validatesAndWritesSnapshotToCacheAndArchive() {
    var snapshot = snapshot(OffsetDateTime.ofInstant(NOW, ZoneOffset.ofHours(8)));
    AtomicReference<MarketOverview> cached = new AtomicReference<>();
    AtomicReference<MarketOverview> archived = new AtomicReference<>();
    QuoteProvider provider = market -> snapshot;
    MarketOverviewStore store = writableStore(cached);
    MarketOverviewArchive archive = writableArchive(archived);
    var service = new MarketIngestionService(
        provider, store, archive, Clock.fixed(NOW, ZoneOffset.UTC));

    var result = service.collect("CN");

    assertThat(result).isSameAs(snapshot);
    assertThat(cached.get()).isSameAs(snapshot);
    assertThat(archived.get()).isSameAs(snapshot);
  }

  @Test
  void rejectsSnapshotWhoseDataTimeIsInTheFuture() {
    var future = snapshot(OffsetDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC));
    var service = new MarketIngestionService(
        market -> future,
        writableStore(new AtomicReference<>()),
        writableArchive(new AtomicReference<>()),
        Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.collect("CN"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dataTime");
  }

  private static MarketOverviewStore writableStore(AtomicReference<MarketOverview> target) {
    return new MarketOverviewStore() {
      @Override
      public Optional<MarketOverview> find(String marketCode) {
        return Optional.ofNullable(target.get());
      }

      @Override
      public void save(MarketOverview snapshot) {
        target.set(snapshot);
      }
    };
  }

  private static MarketOverviewArchive writableArchive(AtomicReference<MarketOverview> target) {
    return new MarketOverviewArchive() {
      @Override
      public Optional<MarketOverview> findLatest(String marketCode) {
        return Optional.ofNullable(target.get());
      }

      @Override
      public void save(MarketOverview snapshot) {
        target.set(snapshot);
      }
    };
  }

  private static MarketOverview snapshot(OffsetDateTime dataTime) {
    return new MarketOverview(
        "CN",
        MarketSessionStatus.TRADING,
        LocalDate.of(2026, 9, 11),
        dataTime,
        MarketOverview.DataStatus.REALTIME,
        List.of(),
        new MarketOverview.BreadthData(1, 1, 0, 0, 0),
        new MarketOverview.TurnoverData("0", "0", List.of()),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        dataTime,
        "v1");
  }
}
