package cn.zhishi.stock.export.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.export.domain.ExportAuditEvent;
import cn.zhishi.stock.export.domain.ExportAuditLog;
import cn.zhishi.stock.export.domain.ExportCell;
import cn.zhishi.stock.export.domain.ExportColumn;
import cn.zhishi.stock.export.domain.ExportDataSource;
import cn.zhishi.stock.export.domain.ExportFileStore;
import cn.zhishi.stock.export.domain.ExportFileWriter;
import cn.zhishi.stock.export.domain.ExportJob;
import cn.zhishi.stock.export.domain.ExportJobStatus;
import cn.zhishi.stock.export.domain.ExportJobStore;
import cn.zhishi.stock.export.domain.ExportPolicy;
import cn.zhishi.stock.export.domain.ExportRateLimiter;
import cn.zhishi.stock.export.domain.ExportRequest;
import cn.zhishi.stock.export.domain.ExportTable;
import cn.zhishi.stock.export.domain.ExportType;
import cn.zhishi.stock.export.domain.RankingExportFilters;
import cn.zhishi.stock.export.domain.StockRankingColumn;
import java.time.Clock;
import java.time.Duration;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 导出作业用例（EXP-01~EXP-04）。
 *
 * <p>用假端口而不是 Testcontainers：这里要钉死的是**作业状态机与错误分类**——
 * "什么时候同步报错、什么时候落成 FAILED、什么时候报 404"，
 * 而这些问题与 Redis / 卷的实现无关。适配器各自的正确性由 {@code infrastructure} 下的测试负责。
 *
 * <h2>生成线程是手动驱动的</h2>
 * 用例层把生成交给 {@code Executor}，测试里换成一个**队列**，
 * 于是"受理之后、生成之前"这个中间态可以被稳定地断言，而不是靠 sleep 去撞一个竞态。
 */
class ExportJobServiceTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-22T07:55:00Z"), ZONE);

  private static final String EXPORT_ID = "1";

  private static final AtomicLong IDS = new AtomicLong(1);

  private final InMemoryJobStore jobs = new InMemoryJobStore();
  private final InMemoryFileStore files = new InMemoryFileStore();
  private final RecordingAuditLog audit = new RecordingAuditLog();
  private final RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
  private final Deque<Runnable> pending = new ArrayDeque<>();

  /** 取数端口要抛的异常；非空时 {@link #createAndDrain()} 会走生成失败分支。 */
  private RuntimeException dataSourceFailure;

  /** 最近一次真正交给写出器的表，用来断言列顺序等映射结果。 */
  private ExportTable renderedTable;

  /** 最近一次取数端口收到的请求，用来断言"用例层校验后的列选择"被原样传下去。 */
  private ExportRequest renderedRequest;

  @BeforeEach
  void reset() {
    jobs.stored.clear();
    files.written.clear();
    audit.events.clear();
    rateLimiter.calls.clear();
    pending.clear();
    dataSourceFailure = null;
    renderedTable = null;
    renderedRequest = null;
    IDS.set(1);
  }

  // ---------- EXP-01 ----------

  @Test
  void acceptedJobBecomesCompletedWithReadableFile() {
    ExportJobAccepted accepted = service(CLOCK).create(7L, request());

    assertThat(accepted.exportId()).isEqualTo(EXPORT_ID);
    assertThat(accepted.status()).isEqualTo(ExportJobStatus.QUEUED.name());
    assertThat(accepted.exportType()).isEqualTo(ExportType.STOCK_RANKING);

    drain();

    ExportJobView view = service(CLOCK).status(7L, EXPORT_ID);
    assertThat(view.status()).isEqualTo(ExportJobStatus.COMPLETED.name());
    assertThat(view.progress()).isEqualTo(100);
    assertThat(view.rowCount()).isEqualTo(2);
    assertThat(view.fileName()).endsWith(".xlsx");
    assertThat(view.error()).isNull();
    assertThat(files.written).containsKey(EXPORT_ID);
  }

  /** 受理时进度只能是 0：生成恰好两个可观测阶段，编一个中间百分比是纯粹的虚构。 */
  @Test
  void queuedJobReportsZeroProgressAndNoFile() {
    service(CLOCK).create(7L, request());

    ExportJobView view = service(CLOCK).status(7L, EXPORT_ID);
    assertThat(view.status()).isEqualTo(ExportJobStatus.QUEUED.name());
    assertThat(view.progress()).isZero();
    assertThat(view.fileName()).isNull();
    // 未就绪时 rowCount 是 null 而不是 0：0 会让"零行结果"与"还没跑"看起来一样。
    assertThat(view.rowCount()).isNull();
  }

  @Test
  void rejectsRankingTypeOutsideWhitelistBeforeAcceptingTheJob() {
    ExportRequest bad = new ExportRequest(
        ExportType.STOCK_RANKING,
        new RankingExportFilters("TOP100", null, null, null, null, null),
        List.of());

    assertThatThrownBy(() -> service(CLOCK).create(7L, bad))
        .isInstanceOf(InvalidExportRequestException.class)
        .hasMessageContaining("rankingType");
    assertThat(jobs.stored).isEmpty();
  }

  @Test
  void rejectsColumnsOutsideWhitelistAndNamesTheOffender() {
    ExportRequest bad = new ExportRequest(
        ExportType.STOCK_RANKING, filters(), List.of("securityCode", "internalCost"));

    assertThatThrownBy(() -> service(CLOCK).create(7L, bad))
        .isInstanceOf(InvalidExportRequestException.class)
        .hasMessageContaining("internalCost");
  }

  /** {@code AI_REPORT} 是契约里的合法取值、只是本版本不交付：必须与"拼错了"分开报。 */
  @Test
  void reportsAiReportAsUnsupportedRatherThanInvalid() {
    ExportRequest aiReport = new ExportRequest(ExportType.AI_REPORT, filters(), List.of());

    assertThatThrownBy(() -> service(CLOCK).create(7L, aiReport))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.TYPE_UNSUPPORTED);
  }

  @Test
  void rateLimitIsCheckedOncePerAcceptedCreate() {
    service(CLOCK).create(7L, request());
    service(CLOCK).create(7L, request());

    assertThat(rateLimiter.calls).hasSize(2);
  }

  @Test
  void surfacesRateLimitAsBusinessExceptionWithoutStoringAJob() {
    rateLimiter.rejectNext = true;

    assertThatThrownBy(() -> service(CLOCK).create(7L, request()))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.RATE_LIMITED);
    assertThat(jobs.stored).isEmpty();
  }

  /** 线程池拒绝时作业必须落成 FAILED：否则它会永远停在 QUEUED，且没有任何地方说明原因。 */
  @Test
  void failedSubmissionLeavesNoJobStuckInQueued() {
    ExportJobService service = service(CLOCK, runnable -> {
      throw new RejectedExecutionException("队列已满");
    });

    assertThatThrownBy(() -> service.create(7L, request()))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.BUSY);

    assertThat(jobs.stored.get(EXPORT_ID).status()).isEqualTo(ExportJobStatus.FAILED);
    assertThat(jobs.stored.get(EXPORT_ID).errorCode())
        .isEqualTo(ExportErrorCode.BUSY.externalCode());
  }

  // ---------- 生成 ----------

  /** 超过 5,000 行不生成、也不截断：静默截断会让用户以为"导全了"。 */
  @Test
  void reportsLimitExceededAsAsyncFailureWithActionableMessage() {
    dataSourceFailure = ExportException.limitExceeded(ExportPolicy.MAX_ROWS + 1);

    createAndDrain();

    ExportJobView view = service(CLOCK).status(7L, EXPORT_ID);
    assertThat(view.status()).isEqualTo(ExportJobStatus.FAILED.name());
    assertThat(view.error()).contains("缩小").contains(String.valueOf(ExportPolicy.MAX_ROWS));
    assertThat(files.written).doesNotContainKey(EXPORT_ID);
  }

  /** 生成阶段的失败也要审计：否则"谁导了但没导成"在 sys_log 上是个空洞。 */
  @Test
  void recordsGenerationFailureInAuditLog() {
    dataSourceFailure = ExportException.limitExceeded(ExportPolicy.MAX_ROWS + 1);

    createAndDrain();

    assertThat(audit.events)
        .anySatisfy(event -> {
          assertThat(event.operation()).isEqualTo("EXPORT_GENERATE");
          assertThat(event.resultStatus()).isEqualTo(ExportAuditEvent.FAILURE);
        });
  }

  /**
   * 内部异常的分类**不能**暴露给用户：前端据此提示"缩小范围"还是"稍后重试"，
   * 而把 {@code NullPointerException} 之类的原文放出去只会误导。
   */
  @Test
  void masksUnexpectedGenerationErrorBehindGenericMessage() {
    dataSourceFailure = new IllegalStateException("内部实现细节不该出现在文案里");

    createAndDrain();

    ExportJobView view = service(CLOCK).status(7L, EXPORT_ID);
    assertThat(view.status()).isEqualTo(ExportJobStatus.FAILED.name());
    assertThat(view.error()).isEqualTo("生成过程出错，请稍后重试");
    assertThat(view.error()).doesNotContain("内部实现细节");
  }

  /** 生成时作业已被删除：没有可推进的对象，静默结束而不是报错。 */
  @Test
  void skipsGenerationWhenJobWasDeletedBeforeItRan() {
    service(CLOCK).create(7L, request());
    service(CLOCK).delete(7L, EXPORT_ID);

    drain();

    assertThat(jobs.stored).isEmpty();
    assertThat(files.written).isEmpty();
  }

  // ---------- 越权与状态 ----------

  /**
   * 别人的作业与不存在的作业必须报**同一句话**：可区分的文案等于告诉攻击者
   * "这个 id 是存在的"（同 HIS-01~HIS-09 的口径）。
   */
  @Test
  void hidesExistenceOfOtherUsersJobs() {
    service(CLOCK).create(7L, request());
    ExportJobService other = service(CLOCK);

    assertThatThrownBy(() -> other.status(8L, EXPORT_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.NOT_FOUND);
    assertThatThrownBy(() -> other.delete(8L, EXPORT_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.NOT_FOUND);
    assertThatThrownBy(() -> other.download(8L, EXPORT_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.NOT_FOUND);
  }

  @Test
  void refusesDownloadWhileJobIsStillQueued() {
    service(CLOCK).create(7L, request());

    assertThatThrownBy(() -> service(CLOCK).download(7L, EXPORT_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.NOT_READY);
  }

  @Test
  void refusesDownloadForFailedJobAndKeepsTheReason() {
    dataSourceFailure = ExportException.limitExceeded(ExportPolicy.MAX_ROWS + 1);

    createAndDrain();

    assertThatThrownBy(() -> service(CLOCK).download(7L, EXPORT_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.FAILED);
  }

  /**
   * COMPLETED 但文件不在（卷被重建、被手工清理）时报 FAILED 而不是 EXPIRED：
   * 保留期还没到，"已过期"是谎报，而且会让用户以为自己的文件是自然地老了。
   */
  @Test
  void reportsFailedWhenCompletedJobLostItsFile() {
    createAndDrain();
    files.written.clear();

    ExportJobService service = service(CLOCK);
    assertThatThrownBy(() -> service.download(7L, EXPORT_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.FAILED);
    assertThat(service.status(7L, EXPORT_ID).error()).contains("重新发起导出");
  }

  // ---------- 过期 ----------

  @Test
  void marksExpiredAfterRetentionAndRefusesDownload() {
    createAndDrain();

    ExportJobService later = service(plusHours(25));

    assertThat(later.status(7L, EXPORT_ID).status()).isEqualTo(ExportJobStatus.EXPIRED.name());
    assertThatThrownBy(() -> later.download(7L, EXPORT_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.EXPIRED);
  }

  /** 契约要求"已过期时保持幂等成功"。记录比文件多活 24 小时正是为了让这条能落地。 */
  @Test
  void deletingAnExpiredJobStillSucceeds() {
    createAndDrain();

    ExportJobService later = service(plusHours(25));
    later.status(7L, EXPORT_ID);

    assertThat(later.delete(7L, EXPORT_ID)).isTrue();
    assertThat(jobs.stored).isEmpty();
  }

  /** 读路径的清理失败不能把查询打成 500：状态仍推进为 EXPIRED，由清理任务重试删文件。 */
  @Test
  void expiryStillReportsExpiredWhenFileDeletionFails() {
    createAndDrain();
    files.failDeletes = true;

    assertThat(service(plusHours(25)).status(7L, EXPORT_ID).status())
        .isEqualTo(ExportJobStatus.EXPIRED.name());
  }

  // ---------- EXP-04 ----------

  @Test
  void deleteRemovesFileAndRecord() {
    createAndDrain();

    assertThat(service(CLOCK).delete(7L, EXPORT_ID)).isTrue();
    assertThat(jobs.stored).isEmpty();
    assertThat(files.written).isEmpty();
  }

  // ---------- 列白名单 ----------

  @Test
  void keepsCallerSuppliedColumnOrder() {
    ExportRequest ordered = new ExportRequest(
        ExportType.STOCK_RANKING, filters(), List.of("changeRate", "securityCode"));

    service(CLOCK).create(7L, ordered);
    drain();

    // 两道都要断言：用例层校验后仍然保序（左），且这个顺序真的落到了表上（右）。
    assertThat(renderedRequest.columns()).containsExactly("changeRate", "securityCode");
    assertThat(renderedTable.columns()).extracting(ExportColumn::key)
        .containsExactly("changeRate", "securityCode");
  }

  /**
   * 未传 {@code columns} 时，落到作业里的**不是空列表而是展开后的默认列集**：
   * 作业记录要能独立回答"这份文件导了哪些列"，而不是留下一个需要再解释一次的哨兵值。
   */
  @Test
  void fallsBackToDefaultColumnsWhenNoneSupplied() {
    createAndDrain();

    assertThat(renderedRequest.columns()).contains("securityCode", "latestPrice");
    assertThat(renderedTable.columns()).extracting(ExportColumn::key)
        .contains("securityCode", "latestPrice")
        .doesNotContain("previousClosePrice");
  }

  // ---------- 夹具与假端口 ----------

  private ExportJobService service(Clock clock) {
    return service(clock, pending::add);
  }

  private void createAndDrain() {
    service(CLOCK).create(7L, request());
    drain();
  }

  private ExportJobService service(Clock clock, Executor executor) {
    ExportDataSource dataSource = request -> {
      if (dataSourceFailure != null) {
        throw dataSourceFailure;
      }
      renderedRequest = request;
      renderedTable = tableFor(request.columns());
      return renderedTable;
    };
    ExportFileWriter writer = value -> ("xlsx:" + value.rowCount()).getBytes();
    return new ExportJobService(
        jobs,
        files,
        dataSource,
        writer,
        rateLimiter,
        audit,
        () -> Long.toString(IDS.getAndIncrement()),
        executor,
        clock);
  }

  private void drain() {
    while (!pending.isEmpty()) {
      pending.poll().run();
    }
  }

  private static Clock plusHours(long hours) {
    return Clock.fixed(CLOCK.instant().plus(Duration.ofHours(hours)), ZONE);
  }

  private static ExportRequest request() {
    return new ExportRequest(ExportType.STOCK_RANKING, filters(), List.of());
  }

  private static RankingExportFilters filters() {
    return new RankingExportFilters("GAINERS", null, null, null, null, null);
  }

  /**
   * 假取数端口按**请求里的列选择**成表，与 {@code StockRankingExportDataSource} 的映射口径一致。
   *
   * <p>写死一份固定列是不行的：那样"列顺序"与"默认列集"两条断言只是在复述夹具本身，
   * 用例层把什么传下来都会通过。列必须由 {@code request.columns()} 推出来，
   * 断言才真正落在"用例层有没有保留调用方点名的顺序"上。
   *
   * <p>格值用占位文本而不是真实快照取值：取值口径属于适配器的职责，
   * 由 {@code StockRankingExportDataSource} 自己的测试覆盖；这里只关心列的**选择与顺序**。
   */
  private static ExportTable tableFor(List<String> columnKeys) {
    List<StockRankingColumn> columns = StockRankingColumn.resolve(columnKeys);
    List<List<ExportCell>> rows = List.of(
        columns.stream().map(column -> ExportCell.text("A-" + column.key())).toList(),
        columns.stream().map(column -> ExportCell.text("B-" + column.key())).toList());
    return new ExportTable(
        "行情榜单 · 涨幅榜",
        List.of("榜单类型：涨幅榜"),
        OffsetDateTime.parse("2026-09-18T15:00:00+08:00"),
        OffsetDateTime.parse("2026-09-22T15:55:00+08:00"),
        "sim-2026-09-18",
        "实时",
        columns.stream().map(StockRankingColumn::column).toList(),
        rows);
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
    private boolean failDeletes;

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
      if (failDeletes) {
        throw new IllegalStateException("卷异常");
      }
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

  private static final class RecordingRateLimiter implements ExportRateLimiter {

    private final List<Long> calls = new ArrayList<>();
    private boolean rejectNext;

    @Override
    public void acquire(long userId) {
      calls.add(userId);
      if (rejectNext) {
        throw ExportException.rateLimited();
      }
    }
  }
}
