package cn.zhishi.stock.backend.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.MarketDataUnavailableException;
import cn.zhishi.stock.market.application.InvalidSecurityQueryException;
import cn.zhishi.stock.market.application.SecurityQuoteBatchQueryService;
import cn.zhishi.stock.market.application.SecurityQuoteBatchQueryService.SecurityQuoteBatchQueryResult;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.backend.web.GlobalExceptionHandler;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 批量行情契约（{@code RESTful-API.md} §8 STK-05）。
 *
 * <h2>这里钉的事实</h2>
 * <ul>
 *   <li>POST {@code /quotes/securities/batch-query} 的响应形状
 *       （{@code items} / {@code missingSecurityIds} / {@code snapshotVersion}）；</li>
 *   <li>错误分支：参数不合法 400 {@code INVALID_REQUEST}、
 *       整批不可用 503 {@code MARKET_DATA_UNAVAILABLE}。</li>
 * </ul>
 *
 * <p>权限不在这里测：{@code standaloneSetup} 没有 Security 过滤链，
 * {@code POST /api/v1/quotes/**} 的 permitAll 由 {@code SecurityConfigurationTest} 钉住。
 */
class QuotesControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private final SecurityQuoteBatchQueryService batchQuery =
      mock(SecurityQuoteBatchQueryService.class);
  private final ObjectMapper objectMapper = mapper();

  @Test
  void returnsTheContractShapeForABatchQuery() throws Exception {
    when(batchQuery.query(any())).thenReturn(new SecurityQuoteBatchQueryResult(
        List.of(snapshot("sim-600000"), snapshot("sim-600519")),
        List.of("sim-999999"),
        "sim-2026-09-27"));

    mvc().perform(post("/api/v1/quotes/securities/batch-query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"securityIds": ["sim-600000", "sim-600519", "sim-999999"]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items[0].security.securityId").value("sim-600000"))
        .andExpect(jsonPath("$.data.items[0].latestPrice").value("12.34"))
        .andExpect(jsonPath("$.data.items[1].security.securityId").value("sim-600519"))
        .andExpect(jsonPath("$.data.missingSecurityIds[0]").value("sim-999999"))
        .andExpect(jsonPath("$.data.snapshotVersion").value("sim-2026-09-27"));
  }

  @Test
  void mapsAnInvalidRequestTo400() throws Exception {
    when(batchQuery.query(any()))
        .thenThrow(new InvalidSecurityQueryException("单次最多查询 50 只证券，收到 51 只"));

    mvc().perform(post("/api/v1/quotes/securities/batch-query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"securityIds": ["sim-600000"]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void mapsAnEmptyBatchTo503() throws Exception {
    when(batchQuery.query(any()))
        .thenThrow(new MarketDataUnavailableException("CN"));

    mvc().perform(post("/api/v1/quotes/securities/batch-query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"securityIds": ["sim-600000"]}
                """))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"));
  }

  // ---------- 装配 ----------

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new QuotesController(batchQuery, CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static ObjectMapper mapper() {
    return Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  private static QuoteSnapshot snapshot(String securityId) {
    SecuritySummary security = new SecuritySummary(
        securityId,
        "SSE." + securityId.substring(4),
        securityId.substring(4),
        "证券 " + securityId,
        "SSE",
        "STOCK",
        "MAIN",
        "LISTED",
        false,
        false,
        2,
        null,
        null);
    return new QuoteSnapshot(
        security,
        "12.00", "12.10", "12.34", "12.50", "11.90", "0.34", "0.0283",
        "1200000", "14700000.00", "0.0125",
        OffsetDateTime.of(LocalDate.of(2026, 9, 27), java.time.LocalTime.of(15, 0), ZoneOffset.ofHours(8)),
        OffsetDateTime.now(CLOCK),
        "sim-2026-09-27",
        MarketOverview.DataStatus.REALTIME,
        null);
  }
}
