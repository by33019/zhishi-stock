package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.domain.MarketOverview;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SimulatedQuoteProviderTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Test
  void normalScenarioProducesDeterministicTradingSnapshot() {
    var provider = new SimulatedQuoteProvider(CLOCK, SimulatedQuoteProvider.Scenario.NORMAL);

    var snapshot = provider.fetch("CN");

    assertThat(snapshot.marketStatus()).isEqualTo(MarketOverview.SessionStatus.TRADING);
    assertThat(snapshot.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
    assertThat(snapshot.indices()).hasSize(4);
    assertThat(snapshot.breadth().riseCount()).isPositive();
    assertThat(snapshot.sectors().get(0).sectorCode()).isEqualTo("BK-AI");
    assertThat(snapshot.rankings().get(0).exchangeCode()).isEqualTo("SH");
    assertThat(snapshot.news().get(0).newsType()).isEqualTo("NEWS");
    assertThat(snapshot.snapshotVersion()).isEqualTo("sim-CN-20260911T100000-normal");
  }

  @Test
  void partialScenarioKeepsResponseUsableAndMarksOneComponentUnavailable() {
    var provider = new SimulatedQuoteProvider(CLOCK, SimulatedQuoteProvider.Scenario.PARTIAL);

    var snapshot = provider.fetch("CN");

    assertThat(snapshot.dataStatus()).isEqualTo(MarketOverview.DataStatus.DELAYED);
    assertThat(snapshot.componentStatus())
        .containsEntry("sectors", MarketOverview.DataStatus.UNAVAILABLE)
        .containsEntry("indices", MarketOverview.DataStatus.REALTIME);
    assertThat(snapshot.sectors()).isEmpty();
  }
}
