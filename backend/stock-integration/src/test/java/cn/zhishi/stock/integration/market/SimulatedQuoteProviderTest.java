package cn.zhishi.stock.integration.market;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.application.RankingCriteria;
import cn.zhishi.stock.market.application.StockRankingQueryService;
import cn.zhishi.stock.market.domain.BreadthCalculator;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedQuoteProviderTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  /** 2026-09-11 是周五、2026-09-12 是周六、2026-09-14 是周一。 */
  private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);

  private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 10);

  /** 周五 10:00（上午连续竞价）。 */
  private static final Clock CLOCK = clockAt("2026-09-11T02:00:00Z");

  /** 周五 08:00（盘前）。 */
  private static final Clock FRIDAY_PRE_OPEN = clockAt("2026-09-11T00:00:00Z");

  /** 周五 20:00（已收盘）。 */
  private static final Clock FRIDAY_AFTER_CLOSE = clockAt("2026-09-11T12:00:00Z");

  /** 周六 10:00（非交易日）。 */
  private static final Clock SATURDAY = clockAt("2026-09-12T02:00:00Z");

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
    TradingCalendarProvider calendar = calendar(CLOCK);
    var provider = new SimulatedQuoteProvider(
        CLOCK,
        SimulatedQuoteProvider.Scenario.NORMAL,
        new SimulatedLimitRuleProvider(),
        calendar,
        batchProvider(CLOCK, calendar));

    List<QuoteRow> preview = provider.fetch("CN").rankings();
    List<QuoteSnapshot> topThree =
        new StockRankingQueryService(batchProvider(CLOCK, calendar), sectorProvider())
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

  /**
   * 契约 §3.6 后：非交易日返回最近有效收盘快照，并把 {@code sessionStatus} 标为 CLOSED，
   * **不视为数据延迟**。
   *
   * <p>修复前这里会返回 {@code tradeDate = 周六} 且 {@code marketStatus = TRADING}——
   * 即"周六正在交易中"。
   */
  @Test
  void nonTradingDayFallsBackToTheLatestCloseAndReportsClosed() {
    var provider = new SimulatedQuoteProvider(SATURDAY, SimulatedQuoteProvider.Scenario.NORMAL);

    var snapshot = provider.fetch("CN");

    assertThat(snapshot.tradeDate()).isEqualTo(FRIDAY);
    assertThat(snapshot.marketStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    // 数据时间必须是那个交易日的收盘时刻，而不是"现在"——
    // 周六 10:00 生成的快照里装的是周五的行情，写成周六 10:00 是假的。
    assertThat(snapshot.dataTime()).isEqualTo(at(FRIDAY, LocalTime.of(15, 0)));
    assertThat(snapshot.dataStatus()).isEqualTo(MarketOverview.DataStatus.REALTIME);
  }

  /**
   * 快照内部必须同源：广度与榜单预览必须来自同一个交易日。
   *
   * <p>修复前广度按"今天"算、榜单按"最近交易日"算，非交易日两者相差一天，
   * 而两处各自都是合法值，没有任何断言会发现。
   *
   * <p>{@link QuoteSnapshot} 不携带 {@code tradeDate}，交易日只体现在
   * {@code dataTime} 与 {@code sequence} 里，因此这里同时断言两件事：
   * 广度确实是对**周五**的行情计数得到的，且整批快照的数据时间落在同一天。
   */
  @Test
  void breadthAndRankingsShareTheSameTradeDateOnANonTradingDay() {
    TradingCalendarProvider calendar = calendar(SATURDAY);
    var provider = new SimulatedQuoteProvider(
        SATURDAY,
        SimulatedQuoteProvider.Scenario.NORMAL,
        new SimulatedLimitRuleProvider(),
        calendar,
        batchProvider(SATURDAY, calendar));

    var snapshot = provider.fetch("CN");
    OffsetDateTime batchDataTime =
        batchProvider(SATURDAY, calendar).fetchBatch("CN").get(0).dataTime();

    assertThat(snapshot.tradeDate()).isEqualTo(FRIDAY);
    assertThat(snapshot.breadth()).isEqualTo(breadthOf(FRIDAY));
    assertThat(batchDataTime).isEqualTo(snapshot.dataTime());
  }

  /** 独立重算某交易日的市场广度，用来证明总览的广度确实是对那一天计的。 */
  private static MarketOverview.BreadthData breadthOf(LocalDate tradeDate) {
    LimitRuleProvider rules = new SimulatedLimitRuleProvider();
    return BreadthCalculator.calculate(
        new SimulatedSecurityQuoteProvider(rules).fetchUniverse("CN", tradeDate),
        rules.rules("CN", tradeDate),
        tradeDate);
  }

  /** 交易日收盘后同样是 CLOSED，且数据时间落在当日收盘而不是 20:00。 */
  @Test
  void afterCloseOnATradingDayReportsClosedWithCloseTimeAsDataTime() {
    var provider =
        new SimulatedQuoteProvider(FRIDAY_AFTER_CLOSE, SimulatedQuoteProvider.Scenario.NORMAL);

    var snapshot = provider.fetch("CN");

    assertThat(snapshot.tradeDate()).isEqualTo(FRIDAY);
    assertThat(snapshot.marketStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    assertThat(snapshot.dataTime()).isEqualTo(at(FRIDAY, LocalTime.of(15, 0)));
  }

  /** 盘前不算收盘：08:00 落在盘前窗口内，数据时间就是此刻。 */
  @Test
  void preOpenOnATradingDayStaysLiveAndUsesNowAsDataTime() {
    var provider =
        new SimulatedQuoteProvider(FRIDAY_PRE_OPEN, SimulatedQuoteProvider.Scenario.NORMAL);

    var snapshot = provider.fetch("CN");

    assertThat(snapshot.tradeDate()).isEqualTo(FRIDAY);
    assertThat(snapshot.marketStatus()).isEqualTo(MarketSessionStatus.PRE_OPEN);
    assertThat(snapshot.dataTime()).isEqualTo(at(FRIDAY, LocalTime.of(8, 0)));
  }

  /**
   * 配置的节假日必须生效：采集侧与查询侧共用同一个 {@code MARKET_HOLIDAYS}，
   * 否则采集任务会以为当天是交易日并落盘快照，而接口按节假日回退到上一交易日。
   */
  @Test
  void configuredHolidayIsTreatedAsANonTradingDay() {
    TradingCalendarProvider holiday = SimulatedTradingCalendarProvider.ofCsv(CLOCK, "2026-09-11");
    var provider = new SimulatedQuoteProvider(
        CLOCK, SimulatedQuoteProvider.Scenario.NORMAL, holiday);

    var snapshot = provider.fetch("CN");

    assertThat(snapshot.tradeDate()).isEqualTo(THURSDAY);
    assertThat(snapshot.marketStatus()).isEqualTo(MarketSessionStatus.CLOSED);
    assertThat(snapshot.dataTime()).isEqualTo(at(THURSDAY, LocalTime.of(15, 0)));
  }

  private static Clock clockAt(String instant) {
    return Clock.fixed(Instant.parse(instant), ZONE);
  }

  private static OffsetDateTime at(LocalDate date, LocalTime time) {
    return OffsetDateTime.of(date, time, ZoneOffset.ofHours(8));
  }

  private static TradingCalendarProvider calendar(Clock clock) {
    return SimulatedTradingCalendarProvider.ofCsv(clock, "");
  }

  private static QuoteSnapshotBatchProvider batchProvider(
      Clock clock, TradingCalendarProvider calendar) {
    LimitRuleProvider rules = new SimulatedLimitRuleProvider();
    SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(rules);
    SecurityMasterProvider master = new SimulatedSecurityMasterProvider(quotes, calendar, clock);
    return new SimulatedQuoteSnapshotProvider(quotes, master, rules, calendar, clock);
  }

  /**
   * 本用例不按板块筛选，但装配仍与生产一致：板块源投影自**同一份**模拟证券全集，
   * 免得测试里出现"生产有 39 个板块、测试里一个都没有"这种假通过。
   */
  private static SimulatedSectorProvider sectorProvider() {
    LimitRuleProvider rules = new SimulatedLimitRuleProvider();
    SecurityQuoteProvider quotes = new SimulatedSecurityQuoteProvider(rules);
    TradingCalendarProvider calendar = SimulatedTradingCalendarProvider.ofCsv(CLOCK, "");
    return new SimulatedSectorProvider(
        new SimulatedSecurityMasterProvider(quotes, calendar, CLOCK));
  }
}
