package cn.zhishi.stock.backend.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.application.MarketStatusQueryService;import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketSessionStatus;
import cn.zhishi.stock.market.domain.MarketStatus;
import cn.zhishi.stock.market.domain.TradingSession;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.watchlist.WatchlistEntry;
import cn.zhishi.stock.system.watchlist.WatchlistErrorCode;
import cn.zhishi.stock.system.watchlist.WatchlistException;
import cn.zhishi.stock.system.watchlist.WatchlistGroup;
import cn.zhishi.stock.system.watchlist.WatchlistItemService;
import cn.zhishi.stock.system.watchlist.WatchlistMembership;
import cn.zhishi.stock.system.watchlist.WatchlistOverview;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 自选中心聚合接口契约（{@code RESTful-API.md} §12.2 WAT-11、WAT-12）。
 *
 * <p>WAT-11 的三个要点在这里被钉住：**降级不失败**（缺行情/缺主数据都只进
 * {@code limitations}）、**{@code latestNewsCount} 为空时是 null 不是 0**、
 * 以及 {@code newsSince} 原样交给用例层解析（不在这里静默忽略）。
 */
class WatchlistOverviewControllerContractTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long GROUP_ID = 7_000_000_000_001L;
  private static final long ITEM_ID = 8_000_000_000_001L;
  private static final String GROUP_ID_TEXT = "7000000000001";
  private static final String SECURITY_ID = "sim-600519";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-20T06:30:00Z"), ZoneId.of("Asia/Shanghai"));

  private final WatchlistItemService items = mock(WatchlistItemService.class);
  private final MarketStatusQueryService marketStatus = mock(MarketStatusQueryService.class);

  // ---------- WAT-11 ----------

  @Test
  void wat11ReturnsGroupsItemsMarketStatusAndTheBatchStatus() throws Exception {
    when(items.overview(USER_ID, null, null)).thenReturn(overview(List.of(entry())));
    when(marketStatus.getStatus("CN", null)).thenReturn(marketStatusValue());

    mvc()
        .perform(get("/api/v1/watchlists/overview").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.groups[0].groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data.groups[0].groupName").value("默认分组"))
        .andExpect(jsonPath("$.data.groups[0].items").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].itemId").value("8000000000001"))
        .andExpect(jsonPath("$.data.items[0].groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data.items[0].security.securityCode").value("600519"))
        .andExpect(jsonPath("$.data.items[0].latestNewsCount").value(nullValue()))
        .andExpect(jsonPath("$.data.marketStatus.marketCode").value("CN"))
        .andExpect(jsonPath("$.data.marketStatus.isTradingDay").value(false))
        .andExpect(jsonPath("$.data.marketStatus.sessionStatus").value("CLOSED"))
        .andExpect(jsonPath("$.data.snapshotVersion").value("sim-20260920"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"))
        .andExpect(jsonPath("$.data.dataTime").value("2026-09-20T15:00:00+08:00"))
        // 本次没有降级：资讯数已接真实口径，"资讯未实现"不再是降级说明
        .andExpect(jsonPath("$.data.limitations.length()").value(0));
  }

  @Test
  void wat11PassesTheGroupFilterThrough() throws Exception {
    when(items.overview(USER_ID, GROUP_ID, null)).thenReturn(overview(List.of(entry())));
    when(marketStatus.getStatus("CN", null)).thenReturn(marketStatusValue());

    mvc()
        .perform(get("/api/v1/watchlists/overview")
            .param("groupId", GROUP_ID_TEXT)
            .principal(authentication()))
        .andExpect(status().isOk());

    verify(items).overview(USER_ID, GROUP_ID, null);
  }

  @Test
  void wat11KeepsThePageAliveAndExplainsWhatIsDegraded() throws Exception {
    when(items.overview(USER_ID, null, null)).thenReturn(new WatchlistOverview(
        List.of(group()),
        List.of(entry()),
        "",
        MarketOverview.DataStatus.UNAVAILABLE,
        null,
        List.of(
            "1 只自选证券不在证券主数据中，仅返回自选关系",
            "1 只自选证券当前没有行情快照，已保留自选关系")));
    when(marketStatus.getStatus("CN", null)).thenReturn(marketStatusValue());

    mvc()
        .perform(get("/api/v1/watchlists/overview").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.snapshotVersion").value(""))
        .andExpect(jsonPath("$.data.dataStatus").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.data.dataTime").value((Object) null))
        .andExpect(jsonPath("$.data.limitations.length()").value(2));
  }

  @Test
  void wat11PassesNewsSinceThroughToTheUseCase() throws Exception {
    when(items.overview(USER_ID, null, "2026-09-01")).thenReturn(overview(List.of(entry())));
    when(marketStatus.getStatus("CN", null)).thenReturn(marketStatusValue());

    mvc()
        .perform(get("/api/v1/watchlists/overview")
            .param("newsSince", "2026-09-01")
            .principal(authentication()))
        .andExpect(status().isOk());

    verify(items).overview(USER_ID, null, "2026-09-01");
  }

  /** 解析在用例层，非法取值必须响亮失败而不是被当成"不过滤"。 */
  @Test
  void wat11RejectsAnUnparseableNewsSince() throws Exception {
    when(items.overview(anyLong(), any(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.INVALID_REQUEST,
            "newsSince 必须是 ISO-8601 时间（如 2026-09-18T10:00:00+08:00）或日期（如 2026-09-18）"));

    mvc()
        .perform(get("/api/v1/watchlists/overview")
            .param("newsSince", "昨天")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value(containsString("newsSince")));
  }

  @Test
  void wat11TreatsABlankNewsSinceAsAbsent() throws Exception {
    // 空白原样透传（"不筛选"的判定在用例层），因此这里桩的就是那串空白
    when(items.overview(USER_ID, null, "  ")).thenReturn(overview(List.of(entry())));
    when(marketStatus.getStatus("CN", null)).thenReturn(marketStatusValue());

    mvc()
        .perform(get("/api/v1/watchlists/overview")
            .param("newsSince", "  ")
            .principal(authentication()))
        .andExpect(status().isOk());
  }

  @Test
  void wat11RejectsANonNumericGroupId() throws Exception {
    mvc()
        .perform(get("/api/v1/watchlists/overview")
            .param("groupId", "abc")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  /** 资讯数来自资讯域，字段名必须出现在响应体里（契约 WAT-06/WAT-11 都列了它）。 */
  @Test
  void wat11ReturnsTheLatestNewsCountOfEachSecurity() throws Exception {
    when(items.overview(USER_ID, null, null)).thenReturn(overview(List.of(entry(3))));
    when(marketStatus.getStatus("CN", null)).thenReturn(marketStatusValue());

    mvc()
        .perform(get("/api/v1/watchlists/overview").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].latestNewsCount").value(3));
  }

  /**
   * 资讯域没给出答案时字段是 {@code null}，不是 {@code 0}。
   *
   * <p>{@code 0} 会被读成"这只股票近期没有资讯"——那是编造；{@code null} 才是"我们不知道"。
   */
  @Test
  void wat11KeepsLatestNewsCountNullWhenUnknown() throws Exception {
    when(items.overview(USER_ID, null, null)).thenReturn(overview(List.of(entry())));
    when(marketStatus.getStatus("CN", null)).thenReturn(marketStatusValue());

    mvc()
        .perform(get("/api/v1/watchlists/overview").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].latestNewsCount").value(nullValue()));
  }

  // ---------- WAT-12 ----------

  @Test
  void wat12ReturnsAMapKeyedBySecurityId() throws Exception {
    Map<String, WatchlistMembership> membership = new LinkedHashMap<>();
    membership.put(SECURITY_ID, new WatchlistMembership(GROUP_ID, "默认分组", ITEM_ID));
    when(items.membership(USER_ID, SECURITY_ID)).thenReturn(membership);

    mvc()
        .perform(get("/api/v1/watchlists/membership")
            .param("securityIds", SECURITY_ID)
            .principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data." + SECURITY_ID + ".groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data." + SECURITY_ID + ".groupName").value("默认分组"))
        .andExpect(jsonPath("$.data." + SECURITY_ID + ".itemId").value("8000000000001"));
  }

  @Test
  void wat12PassesTheRawCsvThroughSoTheServiceOwnsTheRules() throws Exception {
    when(items.membership(USER_ID, SECURITY_ID + ",sim-000001")).thenReturn(Map.of());

    mvc()
        .perform(get("/api/v1/watchlists/membership")
            .param("securityIds", SECURITY_ID + ",sim-000001")
            .principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());

    verify(items).membership(USER_ID, SECURITY_ID + ",sim-000001");
  }

  @Test
  void wat12MapsAnEmptyOrOversizedIdListTo400() throws Exception {
    when(items.membership(anyLong(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.INVALID_REQUEST, "securityIds 必须为 1 至 50 个"));

    mvc()
        .perform(get("/api/v1/watchlists/membership")
            .param("securityIds", "")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void wat12RequiresTheParameter() throws Exception {
    when(items.membership(anyLong(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.INVALID_REQUEST, "securityIds 必须为 1 至 50 个"));

    mvc()
        .perform(get("/api/v1/watchlists/membership").principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  // ---------- 小工具 ----------

  /**
   * 独立 MockMvc 的默认 ObjectMapper 未注册 JavaTimeModule，且 Spring 的
   * Jackson2ObjectMapperBuilder 默认不关闭 WRITE_DATES_AS_TIMESTAMPS（该开关是 Spring Boot
   * 自动配置打开的），会把 {@code dataTime} 序列化成 epoch 数字。
   * 这里显式对齐线上配置，使契约测试断言的是真实线上格式（ISO 字符串）。
   */
  private MockMvc mvc() {
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    return MockMvcBuilders.standaloneSetup(
            new WatchlistOverviewController(items, marketStatus, CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static WatchlistOverview overview(List<WatchlistEntry> entries) {
    return new WatchlistOverview(
        List.of(group()),
        entries,
        "sim-20260920",
        MarketOverview.DataStatus.REALTIME,
        OffsetDateTime.parse("2026-09-20T15:00:00+08:00"),
        List.of());
  }

  private static WatchlistGroup group() {
    return new WatchlistGroup(GROUP_ID, USER_ID, "默认分组", 0, true, 0, 1);
  }

  private static WatchlistEntry entry() {
    return entry(null);
  }

  private static WatchlistEntry entry(Integer latestNewsCount) {
    return new WatchlistEntry(
        ITEM_ID, GROUP_ID, 600_519L, 0, 0, OffsetDateTime.now(CLOCK),
        new cn.zhishi.stock.market.domain.SecuritySummary(
            SECURITY_ID, "SH.600519", "600519", "模拟证券600519", "SH", "STOCK", "MAIN",
            "LISTED", false, false, 2, null, null),
        null,
        latestNewsCount);
  }

  private static MarketStatus marketStatusValue() {
    return new MarketStatus(
        "CN",
        LocalDate.of(2026, 9, 20),
        false,
        MarketSessionStatus.CLOSED,
        TradingSession.CLOSED,
        null,
        OffsetDateTime.parse("2026-09-20T15:00:00+08:00"));
  }

  private static UsernamePasswordAuthenticationToken authentication() {
    var principal = new AccessTokenPrincipal(
        USER_ID, "demo", Set.of("watchlist:read"), "jti-1", Instant.parse("2026-09-20T07:00:00Z"));
    return new UsernamePasswordAuthenticationToken(principal, "token", Set.of());
  }
}
