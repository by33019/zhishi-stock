package cn.zhishi.stock.backend.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.news.application.InvalidNewsQueryException;
import cn.zhishi.stock.news.application.NewsNotFoundException;
import cn.zhishi.stock.news.application.NewsQueryService;
import cn.zhishi.stock.news.domain.NewsDetail;
import cn.zhishi.stock.news.domain.NewsOptions;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsPage;
import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationSummary;
import cn.zhishi.stock.news.domain.NewsSourceType;
import cn.zhishi.stock.news.domain.NewsSummary;
import cn.zhishi.stock.news.domain.NewsSyncStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.news.domain.NewsTimeRange;
import cn.zhishi.stock.news.domain.NewsType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 资讯接口契约（{@code RESTful-API.md} §11.1 NEWS-01~04、§9.2 STK-10、§10 SEC-07）。
 *
 * <h2>为什么这里桩的是 {@link NewsQueryService} 而不是真实服务</h2>
 *
 * <p>本测试钉的是**HTTP 面**：路由（{@code /news/sync-status} 不能被 {@code /news/{newsId}} 吞掉）、
 * 查询参数绑定、统一壳与字段名、以及异常到状态码的映射。这些在用例层看不到。
 *
 * <p>业务规则（可见性过滤链、去重折叠、关联解析、时间区间口径）由
 * {@code NewsQueryServiceTest} 的 39 项覆盖；在这里重造一套稿件语料只会得到
 * 两份**会各自漂移**的夹具，而口径分歧不会报错。
 *
 * <p>因此桩数据是**手写的极小值**，不是模拟源的产出：断言里出现的 {@code publishedAt}、
 * {@code confidenceScore} 必须与模拟算法无关，否则它们会随算法漂移而失效。
 *
 * <p>序列化器与其它契约测试同形（{@code featuresToDisable(WRITE_DATES_AS_TIMESTAMPS)}）：
 * 独立 {@code MockMvc} 不继承应用的 Jackson 配置，少了这一句时间字段会变成数组。
 */
class NewsControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-18T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final OffsetDateTime PUBLISHED_AT =
      OffsetDateTime.of(2026, 9, 18, 10, 0, 0, 0, ZoneOffset.ofHours(8));

  private static final OffsetDateTime SYNCED_AT =
      OffsetDateTime.of(2026, 9, 18, 9, 58, 0, 0, ZoneOffset.ofHours(8));

  // ---------- NEWS-01 ----------

  @Test
  void returnsNewsListContract() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.browse(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new NewsPage(
            List.of(summary("1", NewsType.NEWS, "模拟证券600519 发布业绩预告")),
            1, 20, 1, 1, false,
            SYNCED_AT,
            MarketOverview.DataStatus.REALTIME));

    mvc(service).perform(get("/api/v1/news"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        // 扁平分页：items 与分页字段同级（同 QTE-01 的 StockRanking），不是嵌套 PageData
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.totalPages").value(1))
        .andExpect(jsonPath("$.data.hasNext").value(false))
        // 契约 §3.6：资讯响应必须区分 publishedAt / collectedAt / 平台最近成功同步时间
        .andExpect(jsonPath("$.data.lastSuccessfulSyncAt").value("2026-09-18T09:58:00+08:00"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"))
        .andExpect(jsonPath("$.data.items[0].newsId").value("1"))
        .andExpect(jsonPath("$.data.items[0].newsType").value("NEWS"))
        .andExpect(jsonPath("$.data.items[0].title").value("模拟证券600519 发布业绩预告"))
        .andExpect(jsonPath("$.data.items[0].summary").value("摘要"))
        .andExpect(jsonPath("$.data.items[0].sourceName").value("模拟交易所"))
        .andExpect(jsonPath("$.data.items[0].authorName").value("记者"))
        .andExpect(jsonPath("$.data.items[0].publishedAt").value("2026-09-18T10:00:00+08:00"))
        .andExpect(jsonPath("$.data.items[0].collectedAt").value("2026-09-18T10:01:00+08:00"))
        .andExpect(jsonPath("$.data.items[0].originalUrl").value("https://example.com/news/1"))
        .andExpect(jsonPath("$.data.items[0].originalAccessStatus").value("AVAILABLE"));
  }

  /**
   * 关联里的 {@code targetId} 必须是**对外标识**而不是库里的代理键。
   *
   * <p>返回 bigint 时前端拼出的跳转链接会 404，而且不会有任何后端测试变红
   * （M2-06 / M2-11 已各踩过一次）。
   */
  @Test
  void relationTargetIdsAreOutwardIdentifiers() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.browse(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new NewsPage(
            List.of(summary("1", NewsType.NEWS, "标题")),
            1, 20, 1, 1, false, SYNCED_AT, MarketOverview.DataStatus.REALTIME));

    mvc(service).perform(get("/api/v1/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].relations.length()").value(3))
        .andExpect(jsonPath("$.data.items[0].relations[0].targetType").value("SECURITY"))
        .andExpect(jsonPath("$.data.items[0].relations[0].targetId").value("stub-600519"))
        .andExpect(jsonPath("$.data.items[0].relations[0].targetCode").value("600519"))
        .andExpect(jsonPath("$.data.items[0].relations[0].targetName").value("模拟证券600519"))
        .andExpect(jsonPath("$.data.items[0].relations[0].relationMethod").value("EXPLICIT"))
        .andExpect(jsonPath("$.data.items[0].relations[0].confidenceScore").value(1.0))
        .andExpect(jsonPath("$.data.items[0].relations[1].targetType").value("SECTOR"))
        .andExpect(jsonPath("$.data.items[0].relations[1].targetId").value("stub-bk-industry"))
        .andExpect(jsonPath("$.data.items[0].relations[2].targetType").value("MARKET"))
        .andExpect(jsonPath("$.data.items[0].relations[2].targetId").value("CN"));
  }

  /** 查询参数必须原样透传到用例层——参数名写错时接口不会报错，只会静默不筛选。 */
  @Test
  void passesQueryParametersThrough() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.browse(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new NewsPage(List.of(), 2, 5, 0, 0, false, null,
            MarketOverview.DataStatus.UNAVAILABLE));

    mvc(service).perform(get("/api/v1/news")
            .param("newsTypes", "NEWS,ANNOUNCEMENT")
            .param("securityId", "stub-600519")
            .param("sectorId", "stub-bk-industry")
            .param("marketCode", "CN")
            .param("startAt", "2026-09-01")
            .param("endAt", "2026-09-18")
            .param("keyword", "业绩")
            .param("page", "2")
            .param("size", "5"))
        .andExpect(status().isOk());

    verify(service).browse(
        eq("NEWS,ANNOUNCEMENT"),
        eq("stub-600519"),
        eq("stub-bk-industry"),
        eq("CN"),
        eq("2026-09-01"),
        eq("2026-09-18"),
        eq("业绩"),
        eq(2),
        eq(5));
  }

  @Test
  void rejectsInvalidQueryWith400() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.browse(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new InvalidNewsQueryException("不支持的资讯类型：NEWSX，可选值 [NEWS, ANNOUNCEMENT]"));

    mvc(service).perform(get("/api/v1/news").param("newsTypes", "NEWSX"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持的资讯类型")))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  // ---------- NEWS-02 ----------

  @Test
  void returnsNewsDetailContract() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.detail("1")).thenReturn(new NewsDetail(
        "1",
        NewsType.NEWS,
        "标题",
        "摘要",
        "模拟交易所",
        NewsSourceType.EXCHANGE,
        "记者",
        PUBLISHED_AT,
        PUBLISHED_AT.plusMinutes(1),
        "https://example.com/news/1",
        NewsOriginalAccessStatus.AVAILABLE,
        "zh-CN",
        PUBLISHED_AT.plusDays(30),
        "内容来源：模拟交易所。本页仅展示授权范围内的摘要，完整内容请前往原文。",
        relations()));

    mvc(service).perform(get("/api/v1/news/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.newsId").value("1"))
        .andExpect(jsonPath("$.data.sourceType").value("EXCHANGE"))
        .andExpect(jsonPath("$.data.languageCode").value("zh-CN"))
        .andExpect(jsonPath("$.data.rightsExpireAt").value("2026-10-18T10:00:00+08:00"))
        // 契约 §11.2：详情不返回未经授权的完整正文，只给授权范围内的摘要与版权提示
        .andExpect(jsonPath("$.data.copyrightNotice").isNotEmpty())
        .andExpect(jsonPath("$.data.relations.length()").value(3));
  }

  /**
   * 三种"取不到详情"必须给出**不同的**业务码：撤稿与授权失效都不是"这条资讯不存在"，
   * 合并成一个 404 会让调用方无法区分"链接错了"与"内容被撤了"。
   */
  @Test
  void mapsDetailFailuresToDistinctCodes() throws Exception {
    assertDetailCode(NewsNotFoundException.notFound("1"), "NEWS_NOT_FOUND");
    assertDetailCode(NewsNotFoundException.withdrawn("2"), "NEWS_WITHDRAWN");
    assertDetailCode(NewsNotFoundException.rightsExpired("3"), "NEWS_RIGHTS_EXPIRED");
  }

  // ---------- NEWS-03 ----------

  @Test
  void returnsSyncStatusContract() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.syncStatus()).thenReturn(
        new NewsSyncStatus(NewsSyncStatus.OverallStatus.OK, SYNCED_AT, 120L, 3, 0));

    // 路由钉点：/news/sync-status 必须命中本端点，而不是被 /news/{newsId} 当成 id 吞掉
    mvc(service).perform(get("/api/v1/news/sync-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.overallStatus").value("OK"))
        .andExpect(jsonPath("$.data.lastSuccessfulSyncAt").value("2026-09-18T09:58:00+08:00"))
        .andExpect(jsonPath("$.data.delaySeconds").value(120))
        .andExpect(jsonPath("$.data.availableSourceCount").value(3))
        .andExpect(jsonPath("$.data.failedSourceCount").value(0))
        // 契约：不泄露 Provider 内部错误或配置
        .andExpect(jsonPath("$.data.sources").doesNotExist())
        .andExpect(jsonPath("$.data.message").doesNotExist());
  }

  @Test
  void syncStatusUsesDegradedAndUnavailable() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.syncStatus()).thenReturn(
        new NewsSyncStatus(NewsSyncStatus.OverallStatus.DEGRADED, SYNCED_AT, 120L, 3, 1));

    mvc(service).perform(get("/api/v1/news/sync-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.overallStatus").value("DEGRADED"));
  }

  /** 从未成功同步过时 {@code delaySeconds} 与 {@code lastSuccessfulSyncAt} 必须是 null。 */
  @Test
  void syncStatusKeepsNullsWhenNeverSynced() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.syncStatus()).thenReturn(
        new NewsSyncStatus(NewsSyncStatus.OverallStatus.UNAVAILABLE, null, null, 0, 0));

    mvc(service).perform(get("/api/v1/news/sync-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.overallStatus").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.data.lastSuccessfulSyncAt").doesNotExist())
        .andExpect(jsonPath("$.data.delaySeconds").doesNotExist());
  }

  // ---------- NEWS-04 ----------

  @Test
  void returnsOptionsContract() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.options()).thenReturn(new NewsOptions(
        List.of("NEWS", "ANNOUNCEMENT", "RESEARCH", "OTHER"),
        List.of("MEDIA", "EXCHANGE", "COMPANY", "REGULATOR"),
        new NewsTimeRange(PUBLISHED_AT.minusDays(3), PUBLISHED_AT),
        "仅返回已发布的主记录（跨来源重复稿折叠到主记录）、授权有效来源、未过期内容与已确认关联；"
            + "低置信候选关联不进入列表。"));

    mvc(service).perform(get("/api/v1/news/options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.newsTypes.length()").value(4))
        .andExpect(jsonPath("$.data.newsTypes[0]").value("NEWS"))
        .andExpect(jsonPath("$.data.sourceTypes.length()").value(4))
        .andExpect(jsonPath("$.data.availableTimeRange.startAt")
            .value("2026-09-15T10:00:00+08:00"))
        .andExpect(jsonPath("$.data.availableTimeRange.endAt").value("2026-09-18T10:00:00+08:00"))
        .andExpect(jsonPath("$.data.filterRules").isNotEmpty());
  }

  /**
   * 库里一条资讯都没有时，可用时间范围是**不存在的**，不是"最近一年"。
   *
   * <p>用默认值填充会让前端的日期选择器给出一个看起来合法、实际没有任何数据的区间。
   */
  @Test
  void optionsKeepsEmptyTimeRange() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.options()).thenReturn(new NewsOptions(
        List.of("NEWS"), List.of("MEDIA"), NewsTimeRange.empty(), "规则"));

    mvc(service).perform(get("/api/v1/news/options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.availableTimeRange.startAt").doesNotExist())
        .andExpect(jsonPath("$.data.availableTimeRange.endAt").doesNotExist());
  }

  // ---------- STK-10 ----------

  @Test
  void returnsSecurityNewsContract() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.bySecurity(any(), any(), any(), any(), any(), any()))
        .thenReturn(new NewsPage(List.of(summary("1", NewsType.NEWS, "个股资讯")),
            1, 20, 1, 1, false, SYNCED_AT, MarketOverview.DataStatus.REALTIME));

    mvc(service).perform(get("/api/v1/securities/stub-600519/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].newsId").value("1"))
        .andExpect(jsonPath("$.data.items[0].newsType").value("NEWS"))
        // 契约给 STK-10 的返回参数同样写了 lastSuccessfulSyncAt / dataStatus
        .andExpect(jsonPath("$.data.lastSuccessfulSyncAt").value("2026-09-18T09:58:00+08:00"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"));

    verify(service).bySecurity(
        eq("stub-600519"), isNull(), isNull(), isNull(), isNull(), isNull());
  }

  @Test
  void passesSecurityNewsQueryParametersThrough() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.bySecurity(any(), any(), any(), any(), any(), any()))
        .thenReturn(new NewsPage(List.of(), 3, 10, 0, 0, false, null,
            MarketOverview.DataStatus.UNAVAILABLE));

    mvc(service).perform(get("/api/v1/securities/stub-600519/news")
            .param("newsType", "ANNOUNCEMENT")
            .param("startAt", "2026-09-01")
            .param("endAt", "2026-09-18")
            .param("page", "3")
            .param("size", "10"))
        .andExpect(status().isOk());

    verify(service).bySecurity(
        eq("stub-600519"), eq("ANNOUNCEMENT"), eq("2026-09-01"), eq("2026-09-18"), eq(3), eq(10));
  }

  /** 路径里的证券不存在是 404，不是空页——"代码打错了"与"这只票很安静"是两件事。 */
  @Test
  void unknownSecurityIsNotFound() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.bySecurity(any(), any(), any(), any(), any(), any()))
        .thenThrow(NewsNotFoundException.securityNotFound("stub-999999"));

    mvc(service).perform(get("/api/v1/securities/stub-999999/news"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("SECURITY_NOT_FOUND"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  // ---------- SEC-07 ----------

  @Test
  void returnsSectorNewsContract() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.bySector(any(), any(), any(), any(), any(), any()))
        .thenReturn(new NewsPage(List.of(summary("1", NewsType.NEWS, "板块资讯")),
            1, 20, 1, 1, false, SYNCED_AT, MarketOverview.DataStatus.REALTIME));

    mvc(service).perform(get("/api/v1/sectors/stub-bk-industry/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].newsId").value("1"))
        .andExpect(jsonPath("$.data.lastSuccessfulSyncAt").value("2026-09-18T09:58:00+08:00"))
        .andExpect(jsonPath("$.data.dataStatus").value("REALTIME"));

    verify(service).bySector(
        eq("stub-bk-industry"), isNull(), isNull(), isNull(), isNull(), isNull());
  }

  @Test
  void unknownSectorIsNotFound() throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.bySector(any(), any(), any(), any(), any(), any()))
        .thenThrow(NewsNotFoundException.sectorNotFound("stub-bk-9999"));

    mvc(service).perform(get("/api/v1/sectors/stub-bk-9999/news"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SECTOR_NOT_FOUND"));
  }

  // ---------- 夹具 ----------

  private static void assertDetailCode(RuntimeException thrown, String expectedCode)
      throws Exception {
    NewsQueryService service = mock(NewsQueryService.class);
    when(service.detail(any())).thenThrow(thrown);

    mvc(service).perform(get("/api/v1/news/1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value(expectedCode))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  private static NewsSummary summary(String newsId, NewsType newsType, String title) {
    return new NewsSummary(
        newsId,
        newsType,
        title,
        "摘要",
        "模拟交易所",
        "记者",
        PUBLISHED_AT,
        PUBLISHED_AT.plusMinutes(1),
        "https://example.com/news/" + newsId,
        NewsOriginalAccessStatus.AVAILABLE,
        relations());
  }

  private static List<NewsRelationSummary> relations() {
    return List.of(
        new NewsRelationSummary(
            NewsTargetType.SECURITY, "stub-600519", "600519", "模拟证券600519",
            NewsRelationMethod.EXPLICIT, new BigDecimal("1.00000")),
        new NewsRelationSummary(
            NewsTargetType.SECTOR, "stub-bk-industry", "STUB0002", "桩行业",
            NewsRelationMethod.RULE, new BigDecimal("0.75000")),
        new NewsRelationSummary(
            NewsTargetType.MARKET, "CN", "CN", "CN",
            NewsRelationMethod.EXPLICIT, new BigDecimal("1.00000")));
  }

  private static MockMvc mvc(NewsQueryService service) {
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    return MockMvcBuilders.standaloneSetup(new NewsController(service, CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }
}
