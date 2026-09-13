package cn.zhishi.stock.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Test
  void returnsUnifiedEnvelopeForAvailableMarketOverview() throws Exception {
    var snapshot = snapshot();
    var service = new MarketOverviewQueryService(
        market -> Optional.of(snapshot), market -> Optional.empty());
    MockMvc mvc = mvc(service);

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
    var service = new MarketOverviewQueryService(
        market -> Optional.empty(), market -> Optional.empty());
    MockMvc mvc = mvc(service);

    mvc.perform(get("/api/v1/markets/overview").queryParam("market", "CN"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  private static MockMvc mvc(MarketOverviewQueryService service) {
    return MockMvcBuilders.standaloneSetup(new MarketController(service, CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static MarketOverview snapshot() {
    var now = OffsetDateTime.now(CLOCK);
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
        "v1");
  }
}
