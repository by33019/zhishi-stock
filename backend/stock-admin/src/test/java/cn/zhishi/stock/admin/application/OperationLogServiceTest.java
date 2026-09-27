package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.admin.domain.OperationLogDetail;
import cn.zhishi.stock.admin.domain.OperationLogEntry;
import cn.zhishi.stock.admin.domain.OperationLogQuery;
import cn.zhishi.stock.admin.domain.OperationLogStore;
import cn.zhishi.stock.admin.domain.OperationResultStatus;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.common.audit.AuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 操作日志用例层（契约 §16.2 LOG-01/02）。
 *
 * <h2>这一层负责的三件事</h2>
 * 补时间缺省、拦住超限跨度、读详情时补脱敏。三件都是"接口参数 → 可执行查询"的转换，
 * 因此测试全部围绕**传给仓储的条件**展开，而不是围绕返回值的形状——
 * 返回壳的形状由 {@code PageData} 自己的口径决定，已经有一处测试就够。
 */
class OperationLogServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());

    private final FakeStore store = new FakeStore();
    private final OperationLogService service =
            new OperationLogService(store, new SensitiveParamsRedactor(), CLOCK);

    // ---------- 时间范围 ----------

    @Test
    void defaultsToTheLastSevenDaysWhenNoRangeIsGiven() {
        service.list(emptyQuery());

        assertThat(store.lastCountQuery.endedAt()).isEqualTo(NOW);
        assertThat(store.lastCountQuery.startedAt()).isEqualTo(NOW.minusDays(7));
    }

    @Test
    void keepsAnExplicitlyGivenStartAndOnlyFillsTheEnd() {
        OffsetDateTime start = NOW.minusDays(30);

        service.list(queryWithRange(start, null));

        assertThat(store.lastCountQuery.startedAt()).isEqualTo(start);
        assertThat(store.lastCountQuery.endedAt()).isEqualTo(NOW);
    }

    /**
     * 上限是 90 天（契约原文"默认最多查询 90 天范围"）。
     *
     * <p>边界两侧各测一次：恰好 90 天必须放行，多一天必须拒绝。
     * 只测"超限被拒"会让边界落在哪边变得不可知——而前端就是靠这个边界决定要不要收窄。
     */
    @Test
    void allowsExactlyNinetyDaysAndRejectsOneDayMore() {
        assertThat(service.list(queryWithRange(NOW.minusDays(90), NOW)).total()).isZero();

        assertThatThrownBy(() -> service.list(queryWithRange(NOW.minusDays(91), NOW)))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.LOG_RANGE_TOO_WIDE));
    }

    @Test
    void rejectsARangeWhoseEndIsBeforeItsStart() {
        assertThatThrownBy(() -> service.list(queryWithRange(NOW, NOW.minusDays(1))))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    /** 缺省时用的是注入的 {@code Clock}，不是 {@code OffsetDateTime.now()}。 */
    @Test
    void readsTheDefaultRangeFromTheInjectedClock() {
        Clock earlier = Clock.fixed(Instant.parse("2020-01-01T06:30:00Z"), CLOCK.getZone());
        FakeStore otherStore = new FakeStore();
        OperationLogService other =
                new OperationLogService(otherStore, new SensitiveParamsRedactor(), earlier);

        other.list(emptyQuery());

        assertThat(otherStore.lastCountQuery.endedAt())
                .describedAs("缺省范围的终点必须是这个 Clock 的现在")
                .isEqualTo(OffsetDateTime.ofInstant(earlier.instant(), earlier.getZone()));
        assertThat(otherStore.lastCountQuery.endedAt())
                .describedAs("而不是墙钟的现在——那样这个测试就该变红")
                .isBefore(NOW);
    }

    // ---------- 分页 ----------

    @Test
    void buildsTheStandardPaginationEnvelope() {
        store.total = 42;

        PageData<OperationLogEntry> page = service.list(emptyQuery());

        assertThat(page.items()).hasSize(2);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(42);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
    }

    /**
     * 总数为 0 时不查页面。
     *
     * <p>不是微优化：这一层的查询恰好是"最贵的那些"（宽时间范围 + 无命中索引的过滤组合），
     * 而"没有日志"在刚上线时是常态。
     */
    @Test
    void skipsThePageQueryWhenNothingMatches() {
        store.total = 0;

        PageData<OperationLogEntry> page = service.list(emptyQuery());

        assertThat(page.items()).isEmpty();
        assertThat(store.pageCalls).isZero();
        assertThat(store.lastCountQuery).isNotNull();
    }

    // ---------- 详情 ----------

    @Test
    void redactsTheParamSummaryOnTheWayOut() {
        store.detail = Optional.of(detail("userId=7001;password=Temp@12345"));

        OperationLogDetail detail = service.detail(1L);

        assertThat(detail.paramsSummary())
                .isEqualTo("userId=7001;password=" + SensitiveParamsRedactor.MASK);
        assertThat(detail.paramsSummary()).doesNotContain("Temp@12345");
    }

    @Test
    void reportsAMissingLogAsNotFound() {
        store.detail = Optional.empty();

        assertThatThrownBy(() -> service.detail(404L))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.LOG_NOT_FOUND));
    }

    // ---------- 两侧取值的一致性 ----------

    /**
     * 写入侧的 {@code AuditEvent} 常量与读取侧的枚举必须是同一批取值。
     *
     * <p>它们在类型上没有关系（一边是 {@code String} 常量，一边是 {@code enum}），
     * 因此改名不会编译失败，只会表现为"按 DENIED 筛选永远返回空列表"。
     * 这条断言就是那个会把这件事拦下来的地方。
     */
    @Test
    void writeSideConstantsAndReadSideEnumAgree() {
        assertThat(OperationResultStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        AuditEvent.SUCCESS, AuditEvent.FAILURE, AuditEvent.DENIED);
    }

    // ---------- 夹具 ----------

    private static OperationLogQuery emptyQuery() {
        return new OperationLogQuery(
                null, null, null, null, null, null, null, null, null, null, 1, 20);
    }

    private static OperationLogQuery queryWithRange(
            OffsetDateTime startedAt, OffsetDateTime endedAt) {
        return new OperationLogQuery(
                null, null, null, null, null, null, null, null, startedAt, endedAt, 1, 20);
    }

    private static OperationLogEntry entry(long logId) {
        return new OperationLogEntry(
                logId, 7001L, "admin", "ADMIN_USER_CREATE", null,
                "/api/v1/admin/users", "POST", AuditEvent.SUCCESS, "127.0.0.1", "trace-1",
                NOW);
    }

    private static OperationLogDetail detail(String paramsSummary) {
        return new OperationLogDetail(
                1L, 7001L, null, "admin", "ADMIN_USER_CREATE", null, null,
                "/api/v1/admin/users", "POST", AuditEvent.SUCCESS, paramsSummary,
                "127.0.0.1", "trace-1", NOW);
    }

    private static final class FakeStore implements OperationLogStore {

        private long total;
        private int pageCalls;
        private OperationLogQuery lastCountQuery;
        private Optional<OperationLogDetail> detail = Optional.empty();

        @Override
        public List<OperationLogEntry> page(OperationLogQuery query) {
            pageCalls++;
            return total == 0 ? List.of() : List.of(entry(1L), entry(2L));
        }

        @Override
        public long count(OperationLogQuery query) {
            lastCountQuery = query;
            return total;
        }

        @Override
        public Optional<OperationLogDetail> find(long logId) {
            return detail;
        }
    }
}
