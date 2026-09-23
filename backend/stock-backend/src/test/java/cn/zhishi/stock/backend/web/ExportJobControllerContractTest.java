package cn.zhishi.stock.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.export.application.ExportException;
import cn.zhishi.stock.export.application.ExportJobService;
import cn.zhishi.stock.export.domain.ExportAuditEvent;
import cn.zhishi.stock.export.domain.ExportAuditLog;
import cn.zhishi.stock.export.domain.ExportCell;
import cn.zhishi.stock.export.domain.ExportColumn;
import cn.zhishi.stock.export.domain.ExportColumnFormat;
import cn.zhishi.stock.export.domain.ExportDataSource;
import cn.zhishi.stock.export.domain.ExportFileStore;
import cn.zhishi.stock.export.domain.ExportFileWriter;
import cn.zhishi.stock.export.domain.ExportJob;
import cn.zhishi.stock.export.domain.ExportJobStatus;
import cn.zhishi.stock.export.domain.ExportJobStore;
import cn.zhishi.stock.export.domain.ExportRateLimiter;
import cn.zhishi.stock.export.domain.ExportTable;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyRecord;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 导出接口契约（{@code RESTful-API.md} §9.2 EXP-01~EXP-04、§22.1 限流、§3.7 幂等）。
 *
 * <p>这一层要钉死的是**HTTP 面上的事实**，与用例层、适配器层的用例刻意不重叠：
 * <ul>
 *   <li>创建是 {@code 202} 而不是 {@code 200}——文件此刻并不存在；</li>
 *   <li>{@code Idempotency-Key} 缺失时是 {@code 400 INVALID_REQUEST}，而不是当成没有幂等保护；</li>
 *   <li>同一个键重放**只创建一次**，且返回的是同一次受理结果；</li>
 *   <li>下载的响应头（{@code Content-Disposition} / {@code X-Data-Cutoff-At} / {@code X-Trace-Id}）；</li>
 *   <li>域内业务码到 HTTP 状态的映射（{@code 409} 三个、{@code 422}、{@code 429}、{@code 503}）；</li>
 *   <li>审计事件里的 Web 层事实（URI / 方法 / 客户端 IP / traceId）确实被补上了。</li>
 * </ul>
 *
 * <p>用**真实的** {@link IdempotencyGuard} + 内存 store，而不是 mock 掉它：
 * "重放只创建一次"是 EXP-01 的接口行为，mock 掉守卫等于把这条契约测没了。
 */
class ExportJobControllerContractTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long OTHER_USER_ID = 9_900_000_000_004L;

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-22T07:55:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final OffsetDateTime DATA_TIME =
      OffsetDateTime.parse("2026-09-18T15:00:00+08:00");

  /** 生成出来的那份文件的内容。非 xlsx 也没关系：这里测的是端点，不是写出器。 */
  private static final byte[] GENERATED = "fake-xlsx-bytes".getBytes(StandardCharsets.UTF_8);

  private static final String CREATE_BODY = """
      {"exportType":"STOCK_RANKING","filters":{"rankingType":"GAINERS"},"columns":[]}
      """;

  private final InMemoryJobStore jobs = new InMemoryJobStore();
  private final InMemoryFileStore files = new InMemoryFileStore();
  private final RecordingAuditLog auditLog = new RecordingAuditLog();
  private final StubRateLimiter rateLimiter = new StubRateLimiter();
  private final Deque<Runnable> pending = new ArrayDeque<>();
  private final AtomicLong ids = new AtomicLong(1);
  private final Map<String, IdempotencyRecord> records = new HashMap<>();

  private final IdempotencyGuard idempotency = new IdempotencyGuard(
      new IdempotencyStore() {
        @Override
        public Optional<IdempotencyRecord> find(String scope, long userId, String key) {
          return Optional.ofNullable(records.get(scope + ":" + userId + ":" + key));
        }

        @Override
        public void save(String scope, long userId, String key, IdempotencyRecord record) {
          records.put(scope + ":" + userId + ":" + key, record);
        }
      },
      idempotencyMapper());

  // ---------- EXP-01 创建 ----------

  @Test
  void returns202WithAcceptedJobOnCreate() throws Exception {
    mvc().perform(createRequest("key-1", CREATE_BODY))
        .andExpect(status().isAccepted())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.exportId").value("1"))
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.exportType").value("STOCK_RANKING"))
        .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());
  }

  /**
   * 创建时必须**只有**契约列出的字段。多给一个 {@code fileName} 就会让调用方以为
   * "创建完就能下载"——而文件此刻并不存在，生成还在另一个线程上排队。
   */
  @Test
  void doesNotPromiseAFileThatDoesNotExistYet() throws Exception {
    mvc().perform(createRequest("key-1", CREATE_BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.fileName").doesNotExist())
        .andExpect(jsonPath("$.data.rowCount").doesNotExist())
        .andExpect(jsonPath("$.data.progress").doesNotExist());
  }

  @Test
  void requiresIdempotencyKeyOnCreate() throws Exception {
    mvc().perform(post("/api/v1/export-jobs")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CREATE_BODY)
            .principal(authentication(USER_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  /**
   * 契约 §3.7：同一个键 + 同一个请求体只真正执行一次。
   *
   * <p>断言落在"作业只被创建了一个"上，而不是只看响应体相同——
   * 后者在"又创建了一次、只是返回值恰好一样"时也会通过。
   */
  @Test
  void replaysTheSameAcceptedJobForTheSameIdempotencyKey() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(createRequest("key-1", CREATE_BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.exportId").value("1"));

    mvc.perform(createRequest("key-1", CREATE_BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.exportId").value("1"))
        .andExpect(jsonPath("$.data.status").value("QUEUED"));

    assertThat(jobs.stored).hasSize(1);
  }

  @Test
  void returns409WhenTheSameKeyCarriesADifferentBody() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY)).andExpect(status().isAccepted());

    mvc.perform(createRequest("key-1", """
            {"exportType":"STOCK_RANKING","filters":{"rankingType":"LOSERS"},"columns":[]}
            """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void distinguishesMissingExportTypeFromAnUnknownOne() throws Exception {
    MockMvc mvc = mvc();

    mvc.perform(createRequest("key-1", """
            {"filters":{"rankingType":"GAINERS"}}
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message", containsString("exportType 必填")))
        .andExpect(jsonPath("$.message", containsString("STOCK_RANKING")));

    mvc.perform(createRequest("key-2", """
            {"exportType":"SECTOR_REPORT","filters":{"rankingType":"GAINERS"}}
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("exportType 必须为")));
  }

  /** {@code AI_REPORT} 是契约里的合法取值、只是本版本不交付：必须与"拼错了"分开报。 */
  @Test
  void reportsAiReportAsUnsupportedRatherThanInvalid() throws Exception {
    mvc().perform(createRequest("key-1", """
            {"exportType":"AI_REPORT","filters":{"rankingType":"GAINERS"}}
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EXPORT_TYPE_UNSUPPORTED"));
  }

  @Test
  void returns400ForRankingTypeOutsideWhitelist() throws Exception {
    mvc().perform(createRequest("key-1", """
            {"exportType":"STOCK_RANKING","filters":{"rankingType":"TOP100"}}
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message", containsString("rankingType")));
  }

  /** 提示里必须带上那个不合法的字段名，否则调用方不知道该改哪一个。 */
  @Test
  void returns400ForColumnOutsideWhitelistAndNamesTheOffender() throws Exception {
    mvc().perform(createRequest("key-1", """
            {"exportType":"STOCK_RANKING","filters":{"rankingType":"GAINERS"},
             "columns":["securityCode","internalCost"]}
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message", containsString("internalCost")));
  }

  /** 契约 §22.1：2 次/分钟。超限是 429，且**当场**拒绝，不是受理后落成 FAILED。 */
  @Test
  void returns429WhenTheUserExceedsTheExportRateLimit() throws Exception {
    rateLimiter.reject = true;

    mvc().perform(createRequest("key-1", CREATE_BODY))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("EXPORT_RATE_LIMITED"));

    assertThat(jobs.stored).isEmpty();
  }

  /** 生成线程池满：503 + {@code EXPORT_BUSY}，且作业落成 FAILED 而不是永远停在 QUEUED。 */
  @Test
  void returns503WhenTheGenerationPoolIsSaturated() throws Exception {
    ExportJobService saturated = service(runnable -> {
      throw new RejectedExecutionException("队列已满");
    });

    mvcWith(saturated).perform(createRequest("key-1", CREATE_BODY))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("EXPORT_BUSY"));

    assertThat(jobs.stored.get("1").status()).isEqualTo(ExportJobStatus.FAILED);
  }

  // ---------- EXP-02 状态 ----------

  @Test
  void reportsCompletedJobWithFileAndRowCount() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY));
    drain();

    mvc.perform(get("/api/v1/export-jobs/1").principal(authentication(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.exportId").value("1"))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.progress").value(100))
        .andExpect(jsonPath("$.data.rowCount").value(2))
        .andExpect(jsonPath("$.data.fileName").value(containsString(".xlsx")))
        .andExpect(jsonPath("$.data.error").isEmpty());
  }

  /**
   * 未就绪时 {@code fileName} / {@code rowCount} 必须是 {@code null}，不是空串或 0：
   * 0 会让"零行结果"与"还没跑"看起来一样。
   */
  @Test
  void keepsNotYetGeneratedFieldsNullInsteadOfZero() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY));

    mvc.perform(get("/api/v1/export-jobs/1").principal(authentication(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.progress").value(0))
        .andExpect(jsonPath("$.data.rowCount").value(nullValue()))
        .andExpect(jsonPath("$.data.fileName").value(nullValue()));
  }

  /** 别人的作业与不存在的作业报同一句话：可区分的文案等于告诉攻击者"这个 id 存在"。 */
  @Test
  void hidesOtherUsersJobsBehindTheSameNotFound() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY));

    mvc.perform(get("/api/v1/export-jobs/1").principal(authentication(OTHER_USER_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
    mvc.perform(get("/api/v1/export-jobs/does-not-exist").principal(authentication(USER_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
    mvc.perform(delete("/api/v1/export-jobs/1").principal(authentication(OTHER_USER_ID)))
        .andExpect(status().isNotFound());
  }

  // ---------- EXP-03 下载 ----------

  @Test
  void servesTheFileWithCutoffAndDispositionHeaders() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY));
    drain();

    mvc.perform(get("/api/v1/export-jobs/1/download").principal(authentication(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(header().string("Content-Disposition", containsString("attachment")))
        .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
        .andExpect(header().string("X-Data-Cutoff-At", DATA_TIME.toString()))
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(content().bytes(GENERATED));
  }

  /** 还在排队就要报 {@code 409 EXPORT_NOT_READY}，不能给一份空文件。 */
  @Test
  void refusesDownloadBeforeTheFileExists() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY));

    mvc.perform(get("/api/v1/export-jobs/1/download").principal(authentication(USER_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXPORT_NOT_READY"));
  }

  /** COMPLETED 但文件不在了（卷被重建）报 {@code EXPORT_FAILED}，而不是谎报"已过期"。 */
  @Test
  void reportsFailedRatherThanExpiredWhenTheFileDisappeared() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY));
    drain();
    files.written.clear();

    mvc.perform(get("/api/v1/export-jobs/1/download").principal(authentication(USER_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXPORT_FAILED"));

    mvc.perform(get("/api/v1/export-jobs/1").principal(authentication(USER_ID)))
        .andExpect(jsonPath("$.data.error", containsString("重新发起导出")));
  }

  // ---------- EXP-04 删除 ----------

  @Test
  void deletesJobAndFile() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(createRequest("key-1", CREATE_BODY));
    drain();

    mvc.perform(delete("/api/v1/export-jobs/1").principal(authentication(USER_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.deleted").value(true));

    assertThat(jobs.stored).isEmpty();
    assertThat(files.written).isEmpty();
  }

  // ---------- 审计 ----------

  /**
   * 契约 §22.3 要求审计能回答"谁、从哪、什么时候、请求了什么"。
   * URI / 方法 / 客户端 IP / traceId 只有 Web 层知道，所以这一条只能在契约测试里核对。
   */
  @Test
  void recordsWebLayerFactsIntoTheAuditTrail() throws Exception {
    mvc().perform(createRequest("key-1", CREATE_BODY)
            .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1"))
        .andExpect(status().isAccepted());

    assertThat(auditLog.events).singleElement().satisfies(event -> {
      assertThat(event.operation()).isEqualTo("EXPORT_CREATE");
      assertThat(event.resultStatus()).isEqualTo(ExportAuditEvent.SUCCESS);
      assertThat(event.requestUri()).isEqualTo("/api/v1/export-jobs");
      assertThat(event.httpMethod()).isEqualTo("POST");
      assertThat(event.userId()).isEqualTo(USER_ID);
      // 取第一跳：反向代理会让 getRemoteAddr() 变成代理地址，审计价值几乎为零。
      assertThat(event.ip()).isEqualTo("203.0.113.7");
      assertThat(event.traceId()).isNotBlank();
      assertThat(event.paramsSummary()).contains("rankingType=GAINERS");
    });
  }

  /** 被限流挡下记 {@code DENIED} 而不是 {@code FAILURE}：服务按规则正常工作，运维看的是"谁在刷"。 */
  @Test
  void recordsRateLimitedAttemptsAsDenied() throws Exception {
    rateLimiter.reject = true;

    mvc().perform(createRequest("key-1", CREATE_BODY))
        .andExpect(status().isTooManyRequests());

    assertThat(auditLog.events).singleElement()
        .extracting(ExportAuditEvent::resultStatus)
        .isEqualTo(ExportAuditEvent.DENIED);
  }

  // ---------- 装配 ----------

  private MockMvc mvc() {
    return mvcWith(service(pending::add));
  }

  /**
   * {@code setMessageConverters} 会**替换**掉默认那一组，因此二进制下载要用的
   * {@link ByteArrayHttpMessageConverter} 必须显式放回来——少了它，EXP-03 会因为
   * "找不到能写 {@code byte[]} 的转换器"而返回 500，与真实的接口行为无关。
   */
  private MockMvc mvcWith(ExportJobService service) {
    ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    return MockMvcBuilders.standaloneSetup(
            new ExportJobController(
                service, idempotency, new ExportAuditRecorder(auditLog), CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .setMessageConverters(
            new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private ExportJobService service(Executor executor) {
    ExportDataSource dataSource = request -> table();
    ExportFileWriter writer = table -> GENERATED;
    return new ExportJobService(
        jobs, files, dataSource, writer, rateLimiter, auditLog,
        () -> Long.toString(ids.getAndIncrement()), executor, CLOCK);
  }

  /** 生成是同步排进队列的，由 {@link #drain()} 手动驱动，避免用 sleep 去撞竞态。 */
  private void drain() {
    while (!pending.isEmpty()) {
      pending.poll().run();
    }
  }

  private MockHttpServletRequestBuilder createRequest(String key, String body) {
    return post("/api/v1/export-jobs")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .principal(authentication(USER_ID));
  }

  private static UsernamePasswordAuthenticationToken authentication(long userId) {
    var principal = new AccessTokenPrincipal(
        userId, "demo", Set.of("export:write"), "jti-1",
        Instant.parse("2026-09-22T07:00:00Z"));
    return new UsernamePasswordAuthenticationToken(principal, "token", Set.of());
  }

  /** 与线上一致：注册 JavaTimeModule 且不以时间戳形式写时间。 */
  private static ObjectMapper idempotencyMapper() {
    return JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  private static ExportTable table() {
    return new ExportTable(
        "行情榜单 · 涨幅榜",
        List.of("榜单类型：涨幅榜"),
        DATA_TIME,
        OffsetDateTime.now(CLOCK),
        "sim-2026-09-18",
        "实时",
        List.of(
            new ExportColumn("securityCode", "证券代码", ExportColumnFormat.TEXT),
            new ExportColumn("changeRate", "涨跌幅", ExportColumnFormat.PERCENT)),
        List.of(
            List.of(ExportCell.text("600000"), ExportCell.decimal("0.0300")),
            List.of(ExportCell.text("300001"), ExportCell.decimal("0.2000"))));
  }

  private static final class InMemoryJobStore implements ExportJobStore {

    private final Map<String, ExportJob> stored = new HashMap<>();

    @Override
    public void save(ExportJob job) {
      stored.put(job.exportId(), job);
    }

    @Override
    public Optional<ExportJob> find(String exportId) {
      return Optional.ofNullable(stored.get(exportId));
    }

    @Override
    public void delete(String exportId) {
      stored.remove(exportId);
    }

    @Override
    public List<String> findExpired(int limit) {
      return stored.values().stream()
          .sorted(Comparator.comparing(ExportJob::createdAt))
          .map(ExportJob::exportId)
          .limit(limit)
          .toList();
    }
  }

  private static final class InMemoryFileStore implements ExportFileStore {

    private final Map<String, byte[]> written = new HashMap<>();

    @Override
    public void write(String exportId, String fileName, byte[] content) {
      written.put(exportId, content);
    }

    @Override
    public Optional<byte[]> read(String exportId, String fileName) {
      return Optional.ofNullable(written.get(exportId));
    }

    @Override
    public void delete(String exportId) {
      written.remove(exportId);
    }
  }

  private static final class RecordingAuditLog implements ExportAuditLog {

    private final List<ExportAuditEvent> events = new ArrayList<>();

    @Override
    public void record(ExportAuditEvent event) {
      events.add(event);
    }
  }

  private static final class StubRateLimiter implements ExportRateLimiter {

    private boolean reject;

    @Override
    public void acquire(long userId) {
      if (reject) {
        throw ExportException.rateLimited();
      }
    }
  }
}
