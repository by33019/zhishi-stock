package cn.zhishi.stock.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.SecurityDetailQueryService;
import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.domain.KlinePoint;
import cn.zhishi.stock.market.domain.KlineProvider;
import cn.zhishi.stock.market.domain.KlineQualityStatus;
import cn.zhishi.stock.market.domain.KlineRequest;
import cn.zhishi.stock.market.domain.KlineSeries;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SecurityControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final List<SecuritySummary> UNIVERSE = List.of(
      new SecuritySummary("sim-600000", "SH.600000", "600000", "模拟证券600000", "SH", "STOCK",
          "MAIN", "LISTED", false, false, 2, null, null),
      new SecuritySummary("sim-000001", "SZ.000001", "000001", "模拟证券000001", "SZ", "STOCK",
          "MAIN", "SUSPENDED", true, true, 2, null, null));

  @Test
  void returnsSearchContract() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/search").queryParam("q", "600000"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.items[0].security.fullSymbol").value("SH.600000"))
        .andExpect(jsonPath("$.data.items[0].security.securityCode").value("600000"))
        .andExpect(jsonPath("$.data.items[0].security.securityName").value("模拟证券600000"))
        .andExpect(jsonPath("$.data.items[0].security.exchangeCode").value("SH"))
        .andExpect(jsonPath("$.data.items[0].security.securityType").value("STOCK"))
        .andExpect(jsonPath("$.data.items[0].security.boardCode").value("MAIN"))
        .andExpect(jsonPath("$.data.items[0].security.listingStatus").value("LISTED"))
        .andExpect(jsonPath("$.data.items[0].security.isSt").value(false))
        .andExpect(jsonPath("$.data.items[0].security.isSuspended").value(false))
        .andExpect(jsonPath("$.data.items[0].security.priceScale").value(2))
        .andExpect(jsonPath("$.data.items[0].matchedField").value("CODE"))
        .andExpect(jsonPath("$.data.items[0].highlight").value("600000"));
  }

  /** 拼音字段只服务内部匹配，API 输出必须严格等于文档 §4.1 的 11 个字段。 */
  @Test
  void keepsPinyinFieldsOutOfTheResponseBody() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/search").queryParam("q", "600000"))
        .andExpect(jsonPath("$.data.items[0].security.pinyin").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].security.pinyinAbbr").doesNotExist());
  }

  @Test
  void returnsEmptyItemsWhenNothingMatches() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/search").queryParam("q", "999999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0));
  }

  @Test
  void returns400ForMissingQuery() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/search"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void returns400ForLimitOutsideOneToTwenty() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/search").queryParam("q", "600").queryParam("limit", "21"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void returnsListContractWithPageData() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.totalPages").value(1))
        .andExpect(jsonPath("$.data.hasNext").value(false));
  }

  @Test
  void returns400ForSortFieldOutsideWhitelist() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities").queryParam("sort", "updatedAt,desc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void returns400ForPageBelowOne() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities").queryParam("page", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  /** 板块筛选按成分关系生效：桩板块只含 {@code sim-600000}。 */
  @Test
  void filtersListBySectorId() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities")
            .queryParam("sectorId", StubSectorProvider.INDUSTRY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  /** 板块 ID 不存在时返回空页而不是 400，与其它筛选值同口径。 */
  @Test
  void returnsEmptyPageForUnknownSectorId() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities")
            .queryParam("sectorId", "stub-bk-missing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0))
        .andExpect(jsonPath("$.data.total").value(0));
  }

  // ---------- STK-04 个股快照 ----------

  @Test
  void returnsQuoteContract() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/sim-600000/quote"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.security.fullSymbol").value("SH.600000"))
        .andExpect(jsonPath("$.data.previousClosePrice").value("12.00"))
        .andExpect(jsonPath("$.data.openPrice").value("12.10"))
        .andExpect(jsonPath("$.data.latestPrice").value("12.34"))
        .andExpect(jsonPath("$.data.highPrice").value("12.50"))
        .andExpect(jsonPath("$.data.lowPrice").value("11.90"))
        .andExpect(jsonPath("$.data.changeAmount").value("0.34"))
        .andExpect(jsonPath("$.data.changeRate").value("0.0283"))
        .andExpect(jsonPath("$.data.tradeVolume").value("1200000"))
        .andExpect(jsonPath("$.data.tradeAmount").value("14700000.00"))
        .andExpect(jsonPath("$.data.turnoverRate").value("0.0125"))
        .andExpect(jsonPath("$.data.dataTime").isNotEmpty())
        .andExpect(jsonPath("$.data.serverTime").isNotEmpty())
        .andExpect(jsonPath("$.data.sequence").value("sim-2026-09-11"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"));
  }

  @Test
  void returns404ForUnknownSecurityQuote() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/sim-999999/quote"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("SECURITY_NOT_FOUND"));
  }

  // ---------- STK-07 日 / 周 / 月 K 线 ----------

  @Test
  void returnsKlineContract() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/sim-600000/klines")
            .queryParam("period", "DAY")
            .queryParam("startDate", "2026-08-01")
            .queryParam("endDate", "2026-09-11"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.data.security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.period").value("DAY"))
        .andExpect(jsonPath("$.data.adjustment").value("NONE"))
        .andExpect(jsonPath("$.data.dataCutoffAt").isNotEmpty())
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"))
        .andExpect(jsonPath("$.data.points.length()").value(1))
        .andExpect(jsonPath("$.data.points[0].time").value("2026-09-11"))
        .andExpect(jsonPath("$.data.points[0].openPrice").value("12.10"))
        .andExpect(jsonPath("$.data.points[0].highPrice").value("12.50"))
        .andExpect(jsonPath("$.data.points[0].lowPrice").value("11.90"))
        .andExpect(jsonPath("$.data.points[0].closePrice").value("12.34"))
        .andExpect(jsonPath("$.data.points[0].previousClosePrice").value("12.00"))
        .andExpect(jsonPath("$.data.points[0].changeAmount").value("0.34"))
        .andExpect(jsonPath("$.data.points[0].changeRate").value("0.0283"))
        .andExpect(jsonPath("$.data.points[0].tradeVolume").value("1200000"))
        .andExpect(jsonPath("$.data.points[0].tradeAmount").value("14700000.00"))
        .andExpect(jsonPath("$.data.points[0].turnoverRate").value("0.0125"))
        .andExpect(jsonPath("$.data.points[0].qualityStatus").value("VALID"));
  }

  @Test
  void returns400ForMissingPeriod() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/sim-600000/klines"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  /** 不支持的复权方式必须明确报错，不能静默返回不复权数据。 */
  @Test
  void returns400ForUnsupportedAdjustment() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/sim-600000/klines")
            .queryParam("period", "DAY")
            .queryParam("adjustment", "FORWARD"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("ADJUSTMENT_NOT_SUPPORTED"));
  }

  @Test
  void returns400ForKlineRangeTooLarge() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/sim-600000/klines")
            .queryParam("period", "DAY")
            .queryParam("startDate", "2010-01-01")
            .queryParam("endDate", "2026-09-11"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("KLINE_RANGE_TOO_LARGE"));
  }

  @Test
  void returns404ForUnknownSecurityKlines() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(get("/api/v1/securities/sim-999999/klines").queryParam("period", "DAY"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SECURITY_NOT_FOUND"));
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
    SecurityMasterProvider provider = marketCode ->
        "CN".equals(marketCode) ? UNIVERSE : List.of();
    return MockMvcBuilders.standaloneSetup(
            new SecurityController(
                new SecurityQueryService(provider, sectorProvider()), detailService(), CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  /** 桩板块只含 {@code sim-600000}，用来验证 {@code sectorId} 参数真的参与筛选。 */
  private static StubSectorProvider sectorProvider() {
    return StubSectorProvider.of(Map.of(
        StubSectorProvider.INDUSTRY_ID, List.of("sim-600000")));
  }

  /**
   * 个股快照与 K 线用桩实现：契约测试只关心 JSON 形状、字段名与状态码，
   * 取数与价格生成由 {@code stock-integration} 的测试负责。
   */
  private static SecurityDetailQueryService detailService() {
    QuoteSnapshotProvider snapshots = (securityId, marketCode) ->
        "sim-600000".equals(securityId) && "CN".equals(marketCode)
            ? Optional.of(snapshot())
            : Optional.empty();
    KlineProvider klines = request ->
        "sim-600000".equals(request.securityId()) && "CN".equals(request.marketCode())
            ? Optional.of(series(request))
            : Optional.empty();
    return new SecurityDetailQueryService(snapshots, klines, calendar(), CLOCK);
  }

  private static QuoteSnapshot snapshot() {
    return new QuoteSnapshot(
        UNIVERSE.get(0),
        "12.00", "12.10", "12.34", "12.50", "11.90", "0.34", "0.0283",
        "1200000", "14700000.00", "0.0125",
        OffsetDateTime.of(LocalDate.of(2026, 9, 11), LocalTime.of(15, 0), ZoneOffset.ofHours(8)),
        OffsetDateTime.now(CLOCK),
        "sim-2026-09-11",
        MarketOverview.DataStatus.REALTIME,
        null);
  }

  private static KlineSeries series(KlineRequest request) {
    KlinePoint point = new KlinePoint(
        LocalDate.of(2026, 9, 11),
        "12.10", "12.50", "11.90", "12.34", "12.00", "0.34", "0.0283",
        "1200000", "14700000.00", "0.0125",
        KlineQualityStatus.VALID);
    return new KlineSeries(
        UNIVERSE.get(0),
        request.period(),
        request.adjustment(),
        OffsetDateTime.of(LocalDate.of(2026, 9, 11), LocalTime.of(15, 0), ZoneOffset.ofHours(8)),
        MarketOverview.DataStatus.REALTIME,
        List.of(point));
  }

  private static TradingCalendarProvider calendar() {
    return (marketCode, date) -> "CN".equals(marketCode)
        ? Optional.of(new TradingCalendarDay(
            date, true, date.minusDays(1), date.plusDays(1), List.of(),
            OffsetDateTime.now(CLOCK)))
        : Optional.empty();
  }
}
