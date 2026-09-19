package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.application.RankingCriteria;
import cn.zhishi.stock.market.application.StockRankingQueryService;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverview.QuoteRow;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedQuoteProviderTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Test
  void normalScenarioProducesDeterministicTradingSnapshot() {
    var provider = new SimulatedQuoteProvider(CLOCK, SimulatedQuoteProvider.Scenario.NORMAL);

    var snapshot = provider.fetch("CN");

    assertThat(snapshot.marketStatus()).isEqualTo(MarketSessionStatus.TRADING);
    assertThat(snapshot.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
    assertThat(snapshot.indices()).hasSize(4);
    assertThat(snapshot.breadth().riseCount()).isPositive();
    assertThat(snapshot.sectors().get(0).sectorCode()).isEqualTo("BK-AI");
    assertThat(snapshot.rankings()).hasSize(3);
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

  /**
   * 总览的行情热榜预览必须与 QTE-01 榜单首页前 3 名逐字段一致。
   *
   * <p>此前预览是三个写死的常量：{@code securityId} 用的是主数据里不存在的
   * {@code stock-600519}，导致首页点击个股跳 404；价格也与榜单页对不上。
   * 改为投影之后，这条一致性由构造方式保证，不需要靠约定维持。
   */
  @Test
  void rankingPreviewMatchesTheGainersRankingTopThree() {
    var provider = new SimulatedQuoteProvider(
        CLOCK,
        SimulatedQuoteProvider.Scenario.NORMAL,
        new SimulatedLimitRuleProvider(),
        batchProvider());

    List<QuoteRow> preview = provider.fetch("CN").rankings();
    List<QuoteSnapshot> topThree = new StockRankingQueryService(batchProvider())
        .rank(new RankingCriteria("GAINERS", null, null, null, null, null, 1, 3))
        .items();

    assertThat(preview).hasSize(3);
    for (int index = 0; index < preview.size(); index++) {
      QuoteRow row = preview.get(index);
      QuoteSnapshot snapshot = topThree.get(index);
      assertThat(row.securityId()).isEqualTo(snapshot.security().securityId());
      assertThat(row.securityCode()).isEqualTo(snapshot.security().securityCode());
      assertThat(row.securityName()).isEqualTo(snapshot.security().securityName());
      assertThat(row.exchangeCode()).isEqualTo(snapshot.security().exchangeCode());
      assertThat(row.latestPrice()).isEqualTo(snapshot.latestPrice());
      assertThat(row.changeAmount()).isEqualTo(snapshot.changeAmount());
      assertThat(row.changeRate()).isEqualTo(snapshot.changeRate());
      assertThat(row.tradeVolume()).isEqualTo(snapshot.tradeVolume());
      assertThat(row.tradeAmount()).isEqualTo(snapshot.tradeAmount());
      assertThat(row.turnoverRate()).isEqualTo(snapshot.turnoverRate());
    }
  }

  /**
   * 预览行的 {@code securityId} 必须能在证券主数据里查到，且 {@code sparkline} 是
   * 「开盘 → 最新价」两个真实点位——前端首页拿它做跳转，点不进去就等于没有这一行。
   */
  @Test
  void rankingPreviewRowsAreResolvableAndCarryRealDirectionLine() {
    var provider = new SimulatedQuoteProvider(CLOCK, SimulatedQuoteProvider.Scenario.NORMAL);

    List<QuoteRow> preview = provider.fetch("CN").rankings();

    assertThat(preview).isNotEmpty().allSatisfy(row -> {
      assertThat(row.securityId()).startsWith("sim-");
      assertThat(row.sparkline()).hasSize(2);
      assertThat(row.sparkline().get(1))
          .isEqualTo(new BigDecimal(row.latestPrice()).doubleValue());
    });
  }

  private static QuoteSnapshotBatchProvider batchProvider() {
    LimitRuleProvider rules = new SimulatedLimitRuleProvider();
    SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(rules);
    TradingCalendarProvider calendar = SimulatedTradingCalendarProvider.ofCsv(CLOCK, "");
    SecurityMasterProvider master = new SimulatedSecurityMasterProvider(quotes, calendar, CLOCK);
    return new SimulatedQuoteSnapshotProvider(quotes, master, rules, calendar, CLOCK);
  }
}
