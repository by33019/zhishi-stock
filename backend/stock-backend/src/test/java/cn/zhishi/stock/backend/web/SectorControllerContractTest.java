package cn.zhishi.stock.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.SectorQueryService;
import cn.zhishi.stock.market.application.SectorRankingQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 板块接口契约（{@code RESTful-API.md} §10 SEC-01 / SEC-02 / SEC-03 / SEC-04 / SEC-06）。
 *
 * <p>桩数据刻意包含五种板块状态（见 {@link StubSectorProvider}），因为三条 404 与一条 503
 * 的差别**只在业务码**，用真实模拟源（全部 ACTIVE）根本触发不到。
 *
 * <p>行情批次与 {@code RankingControllerContractTest} 同形：本测试断言的是板块链路
 * 如何消费整批快照，因此快照本身必须是手写的确定值，而不是模拟源生成的。
 */
class SectorControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.of(LocalDate.of(2026, 9, 18), LocalTime.of(15, 0), ZoneOffset.ofHours(8));

  private static final String SEQUENCE = "sim-2026-09-18";

  private static final List<QuoteSnapshot> BATCH = List.of(
      snapshot("sim-600000", "SH", "MAIN", false, "10.20", "0.0300", "1000000", "500000000"),
      snapshot("sim-600001", "SH", "MAIN", false, "13.57", "0.1000", "1500000", "300000000"),
      snapshot("sim-300001", "SZ", "GEM", false, "8.00", "-0.0200", "2000000", "100000000"),
      // 停牌：有行情行但不参与价格与量额统计（M2-05 的模拟源停牌股仍带非零成交量）
      snapshot("sim-000001", "SZ", "MAIN", true, "9.00", null, "3000000", "70000000"));

  /**
   * 桩成分关系：
   * <ul>
   *   <li>{@code 桩行业} = 上涨 + 下跌 + 停牌各一只 → 覆盖"停牌计入 companyCount 但不参与统计"</li>
   *   <li>{@code 桩大类} = 两只上涨股 → 涨幅榜第一</li>
   *   <li>{@code 桩停用板块} 也有成分 → 证明 404 是"停用"导致的，不是"没成分"</li>
   *   <li>{@code 桩全停牌板块} 只有停牌股 → SEC-04 报 503</li>
   *   <li>{@code 桩空板块} 没有任何关系 → SEC-06 报 404</li>
   * </ul>
   */
  private static final StubSectorProvider SECTORS = StubSectorProvider.of(Map.of(
      StubSectorProvider.INDUSTRY_ID, List.of("sim-600000", "sim-300001", "sim-000001"),
      StubSectorProvider.GROUP_ID, List.of("sim-600001", "sim-600000"),
      StubSectorProvider.INACTIVE_ID, List.of("sim-600000"),
      StubSectorProvider.HALTED_ID, List.of("sim-000001")));

  // ---------- SEC-01 板块主数据 ----------

  @Test
  void returnsSectorListContract() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        // 默认只返回 ACTIVE：停用板块不在其中
        .andExpect(jsonPath("$.data.items.length()").value(4))
        .andExpect(jsonPath("$.data.items[0].sectorId").value(StubSectorProvider.GROUP_ID))
        .andExpect(jsonPath("$.data.items[0].sectorCode").value("STUB0001"))
        .andExpect(jsonPath("$.data.items[0].sectorName").value("桩大类"))
        .andExpect(jsonPath("$.data.items[0].sectorType").value("INDUSTRY"))
        .andExpect(jsonPath("$.data.items[0].parentId").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].levelNo").value(1))
        .andExpect(jsonPath("$.data.items[1].parentId").value(StubSectorProvider.GROUP_ID))
        .andExpect(jsonPath("$.data.items[1].levelNo").value(2));
  }

  /** {@code status} 只参与筛选，不进 JSON——契约 §10 SEC-01 只列出 6 个字段。 */
  @Test
  void keepsSectorStatusOutOfTheResponseBody() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors"))
        .andExpect(jsonPath("$.data.items[0].status").doesNotExist());
  }

  @Test
  void returnsInactiveSectorsWhenExplicitlyRequested() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors").queryParam("status", "inactive"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].sectorId").value(StubSectorProvider.INACTIVE_ID));
  }

  @Test
  void filtersSectorListByTypeParentAndKeyword() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors").queryParam("sectorType", "concept"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(2));

    mvc.perform(get("/api/v1/sectors").queryParam("parentId", StubSectorProvider.GROUP_ID))
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].sectorId").value(StubSectorProvider.INDUSTRY_ID));

    mvc.perform(get("/api/v1/sectors").queryParam("keyword", "stub0002"))
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].sectorId").value(StubSectorProvider.INDUSTRY_ID));
  }

  @Test
  void returns400ForSectorTypeOutsideWhitelist() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors").queryParam("sectorType", "THEME"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  // ---------- SEC-02 板块排行 ----------

  @Test
  void returnsSectorRankingContractWithFlatEnvelope() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sector-rankings"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.totalPages").value(1))
        .andExpect(jsonPath("$.data.hasNext").value(false))
        .andExpect(jsonPath("$.data.sectorType").doesNotExist())
        .andExpect(jsonPath("$.data.rankingType").value("GAINERS"))
        .andExpect(jsonPath("$.data.snapshotVersion").value(SEQUENCE))
        .andExpect(jsonPath("$.data.dataTime").isNotEmpty())
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"));
  }

  /** 默认口径是涨幅榜，且兜底键是 {@code sectorCode} 升序。 */
  @Test
  void ordersSectorGainersDescendingByChangeRate() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sector-rankings"))
        .andExpect(jsonPath("$.data.items[0].sectorId").value(StubSectorProvider.GROUP_ID))
        .andExpect(jsonPath("$.data.items[1].sectorId").value(StubSectorProvider.INDUSTRY_ID));
  }

  /**
   * 板块行字段：停牌成分计入 {@code companyCount}，但不参与均价、涨跌幅与量额。
   *
   * <p>桩行业 = 10.20(+3%) + 8.00(-2%) + 停牌 9.00 → 均价 9.10、涨跌幅 0.0050、
   * 成交量 1000000+2000000、成交额 500000000+100000000。
   */
  @Test
  void returnsSectorQuoteFieldsWithSuspendedMembersExcludedFromStatistics() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sector-rankings")
            .queryParam("rankingType", "GAINERS")
            .queryParam("size", "1"))
        .andExpect(jsonPath("$.data.items[0].sectorId").value(StubSectorProvider.GROUP_ID))
        .andExpect(jsonPath("$.data.items[0].sectorCode").value("STUB0001"))
        .andExpect(jsonPath("$.data.items[0].sectorName").value("桩大类"))
        .andExpect(jsonPath("$.data.items[0].sectorType").value("INDUSTRY"))
        .andExpect(jsonPath("$.data.items[0].companyCount").value(2))
        .andExpect(jsonPath("$.data.items[0].averagePrice").value("11.89"))
        .andExpect(jsonPath("$.data.items[0].changeRate").value("0.0650"))
        .andExpect(jsonPath("$.data.items[0].tradeVolume").value("2500000"))
        .andExpect(jsonPath("$.data.items[0].tradeAmount").value("800000000.00"))
        .andExpect(jsonPath("$.data.items[0].leadingStock.security.securityId").value("sim-600001"))
        .andExpect(jsonPath("$.data.items[0].leadingStock.latestPrice").value("13.57"))
        .andExpect(jsonPath("$.data.items[0].leadingStock.changeRate").value("0.1000"))
        .andExpect(jsonPath("$.data.items[0].laggingStock.security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.items[0].dataTime").isNotEmpty())
        .andExpect(jsonPath("$.data.items[0].dataStatus").value("REALTIME"));
  }

  /** 停用板块不进当前排行；全部成分停牌的板块也没有涨跌幅，同样不进涨幅榜。 */
  @Test
  void keepsInactiveAndUnquotedSectorsOutOfTheRanking() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sector-rankings"))
        .andExpect(jsonPath("$.data.items[?(@.sectorId == '" + StubSectorProvider.INACTIVE_ID + "')]")
            .isEmpty())
        .andExpect(jsonPath("$.data.items[?(@.sectorId == '" + StubSectorProvider.HALTED_ID + "')]")
            .isEmpty());
  }

  @Test
  void filtersSectorRankingBySectorType() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sector-rankings").queryParam("sectorType", "INDUSTRY"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.sectorType").value("INDUSTRY"));
  }

  /** 未知口径必须报错，不能回落成默认榜单——静默回落会让调用方拿到"看起来正常"的错误结果。 */
  @Test
  void returns400ForRankingTypeOutsideWhitelist() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sector-rankings").queryParam("rankingType", "TOP100"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void returns400ForPageBelowOneAndSizeAboveOneHundred() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sector-rankings").queryParam("page", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    mvc.perform(get("/api/v1/sector-rankings").queryParam("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  // ---------- SEC-03 板块详情 ----------

  @Test
  void returnsSectorDetailWithParent() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INDUSTRY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sector.sectorId").value(StubSectorProvider.INDUSTRY_ID))
        .andExpect(jsonPath("$.data.sector.sectorName").value("桩行业"))
        .andExpect(jsonPath("$.data.parent.sectorId").value(StubSectorProvider.GROUP_ID))
        .andExpect(jsonPath("$.data.parent.levelNo").value(1))
        .andExpect(jsonPath("$.data.quote.sectorId").value(StubSectorProvider.INDUSTRY_ID))
        .andExpect(jsonPath("$.data.quote.companyCount").value(3));
  }

  /** 一级板块没有父级：{@code parent} 为 {@code null} 而不是空对象。 */
  @Test
  void reportsNullParentForTopLevelSector() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.GROUP_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.parent").doesNotExist());
  }

  /** 契约：「已停用板块可返回历史状态」——详情对停用板块仍返回 200。 */
  @Test
  void returnsDetailForInactiveSectorWith200() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INACTIVE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sector.sectorId").value(StubSectorProvider.INACTIVE_ID));
  }

  @Test
  void returns404ForUnknownSector() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/stub-bk-missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("SECTOR_NOT_FOUND"));
  }

  // ---------- SEC-04 板块行情 ----------

  @Test
  void returnsSectorQuoteContract() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INDUSTRY_ID + "/quote"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.data.sectorId").value(StubSectorProvider.INDUSTRY_ID))
        .andExpect(jsonPath("$.data.sectorCode").value("STUB0002"))
        .andExpect(jsonPath("$.data.companyCount").value(3))
        .andExpect(jsonPath("$.data.averagePrice").value("9.10"))
        .andExpect(jsonPath("$.data.changeRate").value("0.0050"))
        .andExpect(jsonPath("$.data.tradeVolume").value("3000000"))
        .andExpect(jsonPath("$.data.tradeAmount").value("600000000.00"))
        .andExpect(jsonPath("$.data.leadingStock.security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.laggingStock.security.securityId").value("sim-300001"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"));
  }

  @Test
  void returns404ForInactiveSectorQuote() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INACTIVE_ID + "/quote"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SECTOR_INACTIVE"));
  }

  /** 成分全部停牌时"平均涨跌幅"没有定义，报 503 而不是补 0（PRD：历史断点不得补 0）。 */
  @Test
  void returns503WhenNoConstituentHasAUsableQuote() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.HALTED_ID + "/quote"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("SECTOR_QUOTE_NOT_AVAILABLE"));
  }

  // ---------- SEC-06 板块成分股 ----------

  @Test
  void returnsConstituentsContractWithContributionRank() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INDUSTRY_ID + "/constituents"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(1))
        .andExpect(jsonPath("$.data.hasNext").value(false))
        // 默认口径涨幅榜：+3% → -2% → 停牌（无有效行情排末尾）
        .andExpect(jsonPath("$.data.items[0].quote.security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.items[0].quote.latestPrice").value("10.20"))
        .andExpect(jsonPath("$.data.items[0].relationType").value("PRIMARY"))
        .andExpect(jsonPath("$.data.items[0].isPrimary").value(true))
        .andExpect(jsonPath("$.data.items[0].contributionRank").value(1))
        .andExpect(jsonPath("$.data.items[1].quote.security.securityId").value("sim-300001"))
        .andExpect(jsonPath("$.data.items[1].contributionRank").value(2))
        .andExpect(jsonPath("$.data.items[2].quote.security.securityId").value("sim-000001"))
        .andExpect(jsonPath("$.data.items[2].quote.security.isSuspended").value(true))
        .andExpect(jsonPath("$.data.items[2].contributionRank").value(3));
  }

  /**
   * {@code contributionRank} 是**数据属性**：换成跌幅榜时排序反了，贡献度排名不变。
   *
   * <p>否则前端在跌幅榜里看到"第 1 名"会以为是板块龙头，实际却是垫底那只。
   */
  @Test
  void keepsContributionRankIndependentOfRankingType() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INDUSTRY_ID + "/constituents")
            .queryParam("rankingType", "LOSERS"))
        .andExpect(jsonPath("$.data.items[0].quote.security.securityId").value("sim-300001"))
        .andExpect(jsonPath("$.data.items[0].contributionRank").value(2))
        .andExpect(jsonPath("$.data.items[1].quote.security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.items[1].contributionRank").value(1));
  }

  @Test
  void returns404ForInactiveSectorConstituents() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INACTIVE_ID + "/constituents"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SECTOR_INACTIVE"));
  }

  /** 板块存在但没有成分关系时报 404：空页会被读成"该板块确实有 0 只成分股"。 */
  @Test
  void returns404ForSectorWithoutMembersInsteadOfAnEmptyPage() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.EMPTY_ID + "/constituents"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SECTOR_CONSTITUENTS_MISSING"));
  }

  @Test
  void returns400ForMalformedEffectiveDate() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/sectors/" + StubSectorProvider.INDUSTRY_ID + "/constituents")
            .queryParam("effectiveDate", "2026/09/18"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  // ---------- 装配 ----------

  /**
   * 独立 MockMvc 的默认 ObjectMapper 未注册 JavaTimeModule，且 Spring 的
   * Jackson2ObjectMapperBuilder 默认不关闭 WRITE_DATES_AS_TIMESTAMPS。
   * 这里显式对齐线上配置，使契约测试断言的是真实线上格式。
   */
  private static MockMvc mvc() {
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    QuoteSnapshotBatchProvider batchProvider =
        marketCode -> "CN".equals(marketCode) ? BATCH : List.of();
    return MockMvcBuilders.standaloneSetup(new SectorController(
            new SectorQueryService(SECTORS, batchProvider),
            new SectorRankingQueryService(SECTORS, batchProvider),
            CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static QuoteSnapshot snapshot(
      String securityId,
      String exchangeCode,
      String boardCode,
      boolean suspended,
      String latestPrice,
      String changeRate,
      String tradeVolume,
      String tradeAmount) {
    return new QuoteSnapshot(
        summary(securityId, exchangeCode, boardCode, suspended),
        "10.00", "10.10", latestPrice, "10.30", "9.90", "0.20", changeRate,
        tradeVolume, tradeAmount, "0.0100",
        DATA_TIME, DATA_TIME, SEQUENCE, MarketOverview.DataStatus.REALTIME, null);
  }

  private static SecuritySummary summary(
      String securityId, String exchangeCode, String boardCode, boolean suspended) {
    String code = securityId.substring(securityId.indexOf('-') + 1);
    return new SecuritySummary(
        securityId, exchangeCode + "." + code, code, "模拟证券" + code,
        exchangeCode, "STOCK", boardCode, suspended ? "SUSPENDED" : "LISTED",
        false, suspended, 2, null, null);
  }
}
