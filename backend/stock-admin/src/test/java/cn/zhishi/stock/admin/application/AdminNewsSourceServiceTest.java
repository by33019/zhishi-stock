package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.admin.domain.AdminNewsSourcePatch;
import cn.zhishi.stock.admin.domain.AdminNewsSourceQuery;
import cn.zhishi.stock.admin.domain.AdminNewsSourceStore;
import cn.zhishi.stock.admin.domain.NewAdminNewsSource;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 资讯来源管理用例（契约 §17.1 ADM-NEWS-01~04）。
 *
 * <h2>本类钉的是授权状态的推导规则</h2>
 * 契约只用一句"授权状态不能由客户端任意伪造为有效"带过，但它在实现上是一张
 * 真值表：区间覆盖今天 → AUTHORIZED、已到期 → EXPIRED、无区间的新建来源 →
 * UNKNOWN、显式传 AUTHORIZED 只在推导结果也是有效时被接受、人工 SUSPENDED
 * 不被区间编辑悄悄解除。这张表放在内存 stub 上钉住，SQL 层只负责照抄最终值。
 */
class AdminNewsSourceServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    private final InMemorySourceStore store = new InMemorySourceStore();
    private final AdminNewsSourceService service = new AdminNewsSourceService(store, CLOCK);

    // ---------- ADM-NEWS-03：授权状态推导 ----------

    @Test
    void createDerivesAuthorizedWhenRightsPeriodCoversToday() {
        NewsSource created = service.create(command(
                "sim-media-new", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null));

        assertThat(created.authorizationStatus())
                .isEqualTo(NewsSource.AuthorizationStatus.AUTHORIZED);
    }

    /** 授权区间存在但已到期：新建即 EXPIRED，而不是默认的 AUTHORIZED。 */
    @Test
    void createDerivesExpiredWhenRightsPeriodHasEnded() {
        NewsSource created = service.create(command(
                "sim-media-old", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 22), null));

        assertThat(created.authorizationStatus())
                .isEqualTo(NewsSource.AuthorizationStatus.EXPIRED);
    }

    /**
     * 没有授权区间的后台新建来源从 UNKNOWN 起步：V4 的列默认值 AUTHORIZED 是给
     * 采集侧登记模拟来源用的；后台是人事入口，"没登记区间就默认有效"正是
     * 契约禁止的伪造。
     */
    @Test
    void createWithoutRightsPeriodStartsAsUnknown() {
        NewsSource created = service.create(command("sim-media-bare", null, null, null));

        assertThat(created.authorizationStatus())
                .isEqualTo(NewsSource.AuthorizationStatus.UNKNOWN);
    }

    @Test
    void createRejectsAnInvertedRightsPeriod() {
        assertThatThrownBy(() -> service.create(command(
                "sim-media-bad", LocalDate.of(2026, 9, 23), LocalDate.of(2026, 1, 1), null)))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.NEWS_RIGHTS_PERIOD_INVALID));
    }

    @Test
    void createRejectsADuplicateSourceCode() {
        service.create(command("sim-media-dup", null, null, null));

        assertThatThrownBy(() -> service.create(command("sim-media-dup", null, null, null)))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.NEWS_SOURCE_CODE_EXISTS));
    }

    // ---------- ADM-NEWS-04：PATCH 的授权状态规则 ----------

    /** 改名称不动区间：授权状态保持原值（不应被意外重算）。 */
    @Test
    void updateWithoutTouchingRightsKeepsTheCurrentAuthorizationStatus() {
        service.create(command("sim-media-keep", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null));
        NewsSource before = store.findByCode("sim-media-keep").orElseThrow();

        NewsSource after = service.update(
                before.sourceId(), before.version(), AdminNewsSourcePatch.empty()
                        .withName("新名称"));

        assertThat(after.sourceName()).isEqualTo("新名称");
        assertThat(after.authorizationStatus())
                .isEqualTo(NewsSource.AuthorizationStatus.AUTHORIZED);
    }

    /** 把截止日改到过去：重推为 EXPIRED——下一轮采集的授权闸门立即停住。 */
    @Test
    void updateReDerivesExpiredWhenTheMergedPeriodEndsBeforeToday() {
        service.create(command("sim-media-exp", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null));
        NewsSource before = store.findByCode("sim-media-exp").orElseThrow();

        NewsSource after = service.update(
                before.sourceId(), before.version(), AdminNewsSourcePatch.empty()
                        .withRightsValidTo(TODAY.minusDays(1)));

        assertThat(after.authorizationStatus())
                .isEqualTo(NewsSource.AuthorizationStatus.EXPIRED);
    }

    @Test
    void updateRejectsAuthorizedWhenThePeriodHasExpired() {
        service.create(command("sim-media-forge", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 22), null));
        NewsSource before = store.findByCode("sim-media-forge").orElseThrow();

        assertThatThrownBy(() -> service.update(
                before.sourceId(), before.version(), AdminNewsSourcePatch.empty()
                        .withAuthorizationStatus(NewsSource.AuthorizationStatus.AUTHORIZED)))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.NEWS_RIGHTS_PERIOD_INVALID));
    }

    /** 人工暂停比区间更"粘"：编辑区间不会悄悄解除暂停。 */
    @Test
    void updateKeepsSuspensionWhenTheRightsPeriodIsEdited() {
        service.create(command("sim-media-susp", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                null));
        NewsSource created = store.findByCode("sim-media-susp").orElseThrow();
        // 先显式挂起（人工改严），再编辑授权区间
        service.update(created.sourceId(), created.version(),
                AdminNewsSourcePatch.empty().withAuthorizationStatus(
                        NewsSource.AuthorizationStatus.SUSPENDED));
        NewsSource suspended = store.findByCode("sim-media-susp").orElseThrow();

        NewsSource after = service.update(
                suspended.sourceId(), suspended.version(), AdminNewsSourcePatch.empty()
                        .withRightsValidTo(LocalDate.of(2027, 12, 31)));

        assertThat(after.authorizationStatus())
                .isEqualTo(NewsSource.AuthorizationStatus.SUSPENDED);
    }

    // ---------- 乐观锁与 404 ----------

    @Test
    void updateReportsAVersionConflictWithTheCurrentVersion() {
        NewsSource created = service.create(command("sim-media-cas", null, null, null));
        service.update(created.sourceId(), created.version(),
                AdminNewsSourcePatch.empty().withName("别处已改"));

        NewsSource current = store.findByCode("sim-media-cas").orElseThrow();
        assertThatThrownBy(() -> service.update(created.sourceId(), created.version(),
                AdminNewsSourcePatch.empty().withName("旧版本的重试")))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.RESOURCE_VERSION_CONFLICT));
        assertThat(current.sourceName()).isEqualTo("别处已改");
    }

    @Test
    void updateOfAMissingSourceIsA404() {
        assertThatThrownBy(() -> service.update(4242L, 0,
                AdminNewsSourcePatch.empty().withName("不存在")))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.NEWS_SOURCE_NOT_FOUND));
    }

    @Test
    void updateWithoutAnyFieldIsA400() {
        NewsSource created = service.create(command("sim-media-empty", null, null, null));

        assertThatThrownBy(() -> service.update(created.sourceId(), created.version(),
                AdminNewsSourcePatch.empty()))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    // ---------- 读取 ----------

    @Test
    void detailOfAMissingSourceIsA404() {
        assertThatThrownBy(() -> service.detail(4242L))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.NEWS_SOURCE_NOT_FOUND));
    }

    @Test
    void listWrapsThePageInTheStandardEnvelope() {
        service.create(command("sim-media-a", null, null, null));
        service.create(command("sim-media-b", null, null, null));

        PageData<NewsSource> page = service.list(new AdminNewsSourceQuery(
                null, null, null, null, 1, 20));

        assertThat(page.items()).hasSize(2);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isEqualTo(1);
    }

    // ---------- 装配 ----------

    private NewAdminNewsSource command(
            String sourceCode,
            LocalDate from,
            LocalDate to,
            NewsSource.AuthorizationStatus ignored) {
        return new NewAdminNewsSource(
                null, sourceCode, "来源 " + sourceCode, NewsSourceType.MEDIA,
                null, from, to, true, NewsSource.SourceStatus.ACTIVE);
    }

    /** 内存仓储：UPDATE 按 COALESCE 语义落字段，授权状态用例层传入的最终值。 */
    private static class InMemorySourceStore implements AdminNewsSourceStore {

        private final Map<Long, NewsSource> rows = new LinkedHashMap<>();
        private final Map<String, Long> byCodeIndex = new LinkedHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong ids =
                new java.util.concurrent.atomic.AtomicLong(8100);

        @Override
        public List<NewsSource> page(AdminNewsSourceQuery query) {
            return rows.values().stream().toList();
        }

        @Override
        public long count(AdminNewsSourceQuery query) {
            return rows.size();
        }

        @Override
        public Optional<NewsSource> find(long sourceId) {
            return Optional.ofNullable(rows.get(sourceId));
        }

        @Override
        public Optional<NewsSource> findByCode(String sourceCode) {
            return Optional.ofNullable(byCodeIndex.get(sourceCode)).map(rows::get);
        }

        @Override
        public NewsSource insert(
                NewAdminNewsSource command, NewsSource.AuthorizationStatus authorizationStatus) {
            long id = ids.incrementAndGet();
            NewsSource source = new NewsSource(
                    id, command.sourceCode(), command.sourceName(), command.sourceType(),
                    command.homepageUrl(), authorizationStatus,
                    command.rightsValidFrom(), command.rightsValidTo(),
                    command.allowAiAnalysis(), command.status(),
                    null, null, 0);
            rows.put(id, source);
            byCodeIndex.put(source.sourceCode(), id);
            return source;
        }

        @Override
        public Optional<NewsSource> update(
                long sourceId,
                int expectedVersion,
                AdminNewsSourcePatch patch,
                NewsSource.AuthorizationStatus authorizationStatus) {
            NewsSource current = rows.get(sourceId);
            if (current == null || current.version() != expectedVersion) {
                return Optional.empty();
            }
            NewsSource updated = new NewsSource(
                    current.sourceId(),
                    current.sourceCode(),
                    patch.sourceName().orElse(current.sourceName()),
                    current.sourceType(),
                    patch.homepageUrl().orElse(current.homepageUrl()),
                    authorizationStatus,
                    patch.rightsValidFrom().orElse(current.rightsValidFrom()),
                    patch.rightsValidTo().orElse(current.rightsValidTo()),
                    patch.allowAiAnalysis().orElse(current.allowAiAnalysis()),
                    patch.status().orElse(current.status()),
                    current.lastSuccessAt(),
                    current.lastFailureAt(),
                    current.version() + 1);
            rows.put(sourceId, updated);
            return Optional.of(updated);
        }
    }
}
