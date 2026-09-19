package cn.zhishi.stock.backend.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
            new SecurityController(new SecurityQueryService(provider), CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }
}
