package cn.zhishi.stock.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.MarketBreadthQueryService;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.application.MarketStatusQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TradingSession;
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

class MarketControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 11);

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.of(2026, 9, 11, 10, 0, 0, 0, ZoneOffset.ofHours(8));

  /** 与 SimulatedTradingCalendarProvider 一致的 A 股窗口集，使契约断言贴近真实日历。 */
  private static final List<TradingCalendarDay.Window> WINDOWS = List.of(
      new TradingCalendarDay.Window(TradingSession.PRE_OPEN, LocalTime.of(0, 0), LocalTime.of(9, 15)),
      new TradingCalendarDay.Window(
          TradingSession.OPENING_CALL_AUCTION, LocalTime.of(9, 15), LocalTime.of(9, 25)),
      new TradingCalendarDay.Window(TradingSession.PRE_OPEN, LocalTime.of(9, 25), LocalTime.of(9, 30)),
      new TradingCalendarDay.Window(
          TradingSession.MORNING_CONTINUOUS, LocalTime.of(9, 30), LocalTime.of(11, 30)),
      new TradingCalendarDay.Window(
          TradingSession.LUNCH_BREAK, LocalTime.of(11, 30), LocalTime.of(13, 0)),
      new TradingCalendarDay.Window(
          TradingSession.AFTERNOON_CONTINUOUS, LocalTime.of(13, 0), LocalTime.of(14, 57)),
      new TradingCalendarDay.Window(
          TradingSession.CLOSING_CALL_AUCTION, LocalTime.of(14, 57), LocalTime.of(15, 0)));

  @Test
  void returnsUnifiedEnvelopeForAvailableMarketOverview() throws Exception {
    MockMvc mvc = mvc(overviewService());

    mvc.perform(get("/api/v1/markets/overview").queryParam("market", "CN"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.data.marketCode").value("CN"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"));
  }

  @Test
  void returns503AndStableErrorCodeWhenAllMarketDataIsUnavailable() throws Exception {
    MockMvc mvc = mvc(unavailableOverviewService());

    mvc.perform(get("/api/v1/markets/overview").queryParam("market", "CN"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void returnsMarketSessionStatusContract() throws Exception {
    MockMvc mvc = mvc(overviewService());

    mvc.perform(get("/api/v1/markets/CN/status"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.data.marketCode").value("CN"))
        .andExpect(jsonPath("$.data.tradeDate").value("2026-09-11"))
        .andExpect(jsonPath("$.data.isTradingDay").value(true))
        .andExpect(jsonPath("$.data.sessionStatus").value("TRADING"))
        .andExpect(jsonPath("$.data.currentSession").value("MORNING_CONTINUOUS"))
        .andExpect(jsonPath("$.data.nextSessionAt").value("2026-09-11T11:30:00+08:00"))
        .andExpect(jsonPath("$.data.calendarSourceTime").isNotEmpty());
  }

  @Test
  void returns404WithStableCodeForUnsupportedMarket() throws Exception {
    MockMvc mvc = mvc(overviewService());

    mvc.perform(get("/api/v1/markets/US/status"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("MARKET_NOT_FOUND"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void returns400WhenDateParameterIsNotIsoDate() throws Exception {
    MockMvc mvc = mvc(overviewService());

    mvc.perform(get("/api/v1/markets/CN/status").queryParam("date", "2026/09/11"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returnsMarketBreadthContract() throws Exception {
    MockMvc mvc = mvc(overviewService());

    mvc.perform(get("/api/v1/markets/CN/breadth"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.marketCode").value("CN"))
        .andExpect(jsonPath("$.data.riseCount").value(12))
        .andExpect(jsonPath("$.data.fallCount").value(8))
        .andExpect(jsonPath("$.data.flatCount").value(3))
        .andExpect(jsonPath("$.data.suspendedCount").value(2))
        .andExpect(jsonPath("$.data.limitUpCount").value(4))
        .andExpect(jsonPath("$.data.limitDownCount").value(1))
        .andExpect(jsonPath("$.data.totalCount").value(25))
        .andExpect(jsonPath("$.data.dataTime").value("2026-09-11T10:00:00+08:00"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"))
        .andExpect(jsonPath("$.data.snapshotVersion").value("contract-v1"));
  }

  @Test
  void returns503ForBreadthWhenNoSnapshotExists() throws Exception {
    MockMvc mvc = mvc(unavailableOverviewService());

    mvc.perform(get("/api/v1/markets/CN/breadth"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"));
  }

  /**
   * 独立 MockMvc 的默认 ObjectMapper 未注册 JavaTimeModule，会把 LocalDate 序列化成数组
   * （如 [2026,9,11]）；而 Spring 的 Jackson2ObjectMapperBuilder 默认也不关闭
   * WRITE_DATES_AS_TIMESTAMPS，该开关是 Spring Boot 自动配置打开的。
   * 这里显式对齐线上配置，使契约测试断言的是真实线上格式（ISO 字符串）。
   */
  private static MockMvc mvc(MarketOverviewQueryService service) {
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    return MockMvcBuilders.standaloneSetup(new MarketController(
            service,
            statusService(),
            new MarketBreadthQueryService(service),
            CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static MarketOverviewQueryService overviewService() {
    return new MarketOverviewQueryService(
        market -> Optional.of(snapshot()), market -> Optional.empty());
  }

  private static MarketOverviewQueryService unavailableOverviewService() {
    return new MarketOverviewQueryService(market -> Optional.empty(), market -> Optional.empty());
  }

  private static MarketStatusQueryService statusService() {
    TradingCalendarProvider provider = (marketCode, date) -> {
      if (!"CN".equals(marketCode)) {
        return Optional.empty();
      }
      return Optional.of(new TradingCalendarDay(
          date, true, date.minusDays(1), date.plusDays(1), WINDOWS, OffsetDateTime.now(CLOCK)));
    };
    return new MarketStatusQueryService(provider, CLOCK);
  }

  private static MarketOverview snapshot() {
    return new MarketOverview(
        "CN",
        MarketSessionStatus.TRADING,
        FRIDAY,
        DATA_TIME,
        MarketOverview.DataStatus.REALTIME,
        List.of(),
        new MarketOverview.BreadthData(12, 8, 3, 2, 4, 1),
        new MarketOverview.TurnoverData("0", "0", List.of()),
        List.of(),
        List.of(),
        List.of(),
        Map.of("indices", MarketOverview.DataStatus.REALTIME),
        DATA_TIME,
        "contract-v1");
  }
}
