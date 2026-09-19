package cn.zhishi.stock.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.StockRankingQueryService;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RankingControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-19T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.of(LocalDate.of(2026, 9, 18), LocalTime.of(15, 0), ZoneOffset.ofHours(8));

  private static final String SEQUENCE = "sim-2026-09-18";

  private static final List<QuoteSnapshot> BATCH = List.of(
      snapshot("sim-600000", "SH", "MAIN", "0.0300", "500000000"),
      snapshot("sim-300001", "SZ", "GEM", "0.2000", "100000000"),
      snapshot("sim-430001", "BJ", "BSE", "0.3000", "200000000"));

  // ---------- QTE-01 榜单 ----------

  @Test
  void returnsRankingContractWithFlatEnvelope() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings").queryParam("rankingType", "GAINERS"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.items.length()").value(3))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(1))
        .andExpect(jsonPath("$.data.hasNext").value(false))
        .andExpect(jsonPath("$.data.rankingType").value("GAINERS"))
        .andExpect(jsonPath("$.data.snapshotVersion").value(SEQUENCE))
        .andExpect(jsonPath("$.data.dataTime").isNotEmpty())
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"));
  }

  @Test
  void returnsFullQuoteSnapshotPerRow() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings")
            .queryParam("rankingType", "GAINERS")
            .queryParam("size", "1"))
        .andExpect(jsonPath("$.data.items[0].security.securityId").value("sim-430001"))
        .andExpect(jsonPath("$.data.items[0].security.fullSymbol").value("BJ.430001"))
        .andExpect(jsonPath("$.data.items[0].security.securityCode").value("430001"))
        .andExpect(jsonPath("$.data.items[0].security.securityName").value("模拟证券430001"))
        .andExpect(jsonPath("$.data.items[0].security.exchangeCode").value("BJ"))
        .andExpect(jsonPath("$.data.items[0].security.boardCode").value("BSE"))
        .andExpect(jsonPath("$.data.items[0].security.isSt").value(false))
        .andExpect(jsonPath("$.data.items[0].security.isSuspended").value(false))
        .andExpect(jsonPath("$.data.items[0].previousClosePrice").value("10.00"))
        .andExpect(jsonPath("$.data.items[0].latestPrice").value("10.20"))
        .andExpect(jsonPath("$.data.items[0].changeAmount").value("0.20"))
        .andExpect(jsonPath("$.data.items[0].changeRate").value("0.3000"))
        .andExpect(jsonPath("$.data.items[0].tradeVolume").value("1000000"))
        .andExpect(jsonPath("$.data.items[0].tradeAmount").value("200000000"))
        .andExpect(jsonPath("$.data.items[0].turnoverRate").value("0.0100"))
        .andExpect(jsonPath("$.data.items[0].sequence").value(SEQUENCE))
        .andExpect(jsonPath("$.data.items[0].dataStatus").value("REALTIME"));
  }

  /** 契约要求整个榜单使用同一快照版本，因此外层版本号必须等于每一行的序列号。 */
  @Test
  void keepsOuterVersionEqualToEveryRowSequence() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings").queryParam("rankingType", "TURNOVER"))
        .andExpect(jsonPath("$.data.items[0].sequence").value(SEQUENCE))
        .andExpect(jsonPath("$.data.items[1].sequence").value(SEQUENCE))
        .andExpect(jsonPath("$.data.items[2].sequence").value(SEQUENCE))
        .andExpect(jsonPath("$.data.snapshotVersion").value(SEQUENCE));
  }

  @Test
  void ordersGainersDescending() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings").queryParam("rankingType", "GAINERS"))
        .andExpect(jsonPath("$.data.items[0].security.securityId").value("sim-430001"))
        .andExpect(jsonPath("$.data.items[1].security.securityId").value("sim-300001"))
        .andExpect(jsonPath("$.data.items[2].security.securityId").value("sim-600000"));
  }

  @Test
  void ordersTurnoverByTradeAmountDescending() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings").queryParam("rankingType", "TURNOVER"))
        .andExpect(jsonPath("$.data.items[0].security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.items[1].security.securityId").value("sim-430001"))
        .andExpect(jsonPath("$.data.items[2].security.securityId").value("sim-300001"));
  }

  @Test
  void echoesRankingTypeInUpperCase() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings").queryParam("rankingType", "losers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rankingType").value("LOSERS"));
  }

  // ---------- 参数校验 ----------

  @Test
  void returns400ForMissingRankingType() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void returns400ForRankingTypeOutsideWhitelist() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings").queryParam("rankingType", "TOP100"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void returns400ForPageBelowOneAndSizeAboveOneHundred() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings")
            .queryParam("rankingType", "GAINERS").queryParam("page", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    mvc.perform(get("/api/v1/stock-rankings")
            .queryParam("rankingType", "GAINERS").queryParam("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  /**
   * 筛选值不存在返回空页而不是 400：{@code exchangeCodes=XX} 是合法取值，
   * 只是数据里没有。报 400 就把"没有数据"错报成"参数非法"。
   */
  @Test
  void returnsEmptyPageForUnknownFilterValue() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings")
            .queryParam("rankingType", "GAINERS")
            .queryParam("exchangeCodes", "XX"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items.length()").value(0))
        .andExpect(jsonPath("$.data.total").value(0))
        .andExpect(jsonPath("$.data.totalPages").value(0));
  }

  /** 板块关系数据在 M2-07 之前不存在，因此该条件当前必然返回空页。 */
  @Test
  void returnsEmptyPageForSectorId() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings")
            .queryParam("rankingType", "GAINERS")
            .queryParam("sectorId", "bk-ai"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0))
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  void filtersByExchangeCodes() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/stock-rankings")
            .queryParam("rankingType", "GAINERS")
            .queryParam("exchangeCodes", "SH,BJ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.items[0].security.exchangeCode").value("BJ"))
        .andExpect(jsonPath("$.data.items[1].security.exchangeCode").value("SH"));
  }

  /**
   * 独立 MockMvc 的默认 ObjectMapper 未注册 JavaTimeModule，且 Spring 的
   * Jackson2ObjectMapperBuilder 默认不关闭 WRITE_DATES_AS_TIMESTAMPS。
   * 这里显式对齐线上配置，使契约测试断言的是真实线上格式。
   */
  private static MockMvc mvc() {
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    return MockMvcBuilders.standaloneSetup(
            new RankingController(new StockRankingQueryService(batchProvider()), CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static QuoteSnapshotBatchProvider batchProvider() {
    return marketCode -> "CN".equals(marketCode) ? BATCH : List.of();
  }

  private static QuoteSnapshot snapshot(
      String securityId, String exchangeCode, String boardCode, String changeRate, String amount) {
    return new QuoteSnapshot(
        summary(securityId, exchangeCode, boardCode),
        "10.00", "10.10", "10.20", "10.30", "9.90", "0.20", changeRate,
        "1000000", amount, "0.0100",
        DATA_TIME, DATA_TIME, SEQUENCE, MarketOverview.DataStatus.REALTIME, null);
  }

  private static SecuritySummary summary(
      String securityId, String exchangeCode, String boardCode) {
    String code = securityId.substring(securityId.indexOf('-') + 1);
    return new SecuritySummary(
        securityId, exchangeCode + "." + code, code, "模拟证券" + code,
        exchangeCode, "STOCK", boardCode, "LISTED", false, false, 2, null, null);
  }
}
