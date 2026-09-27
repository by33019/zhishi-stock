package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.admin.domain.AdminNewsRelationEntry;
import cn.zhishi.stock.admin.domain.AdminNewsRelationQuery;
import cn.zhishi.stock.admin.domain.AdminNewsRelationStore;
import cn.zhishi.stock.admin.domain.AdminNewsRelationView;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * 资讯关联审核用例（契约 §17.2 ADM-NEWS-05~08）。
 *
 * <h2>本类钉的是审核的不可逆性与目标桥接</h2>
 * 复核一次就定格（二次复核 409）、删除是置 REJECTED 而不是物理删、
 * 重复的手工关联是幂等成功而不是 409、代理键与对外标识在边界换算
 * （进库存 bigint、出网是 sim- 前缀）。这些如果做错，都不会报错——
 * 只会让审计记录失真或让前端拼出 404 的跳转链接。
 */
class AdminNewsRelationServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());
    private static final long REVIEWER = 9001L;

    private final InMemoryRelationStore store = new InMemoryRelationStore();
    private final StubSecurityIdentities securities = new StubSecurityIdentities();
    private final StubSectorIdentities sectors = new StubSectorIdentities();
    private final AdminNewsRelationService service =
            new AdminNewsRelationService(store, securities, sectors, CLOCK);

    // ---------- ADM-NEWS-05：缺省看候选 ----------

    @Test
    void listDefaultsToCandidatesWhenNoStatusIsGiven() {
        service.list(new AdminNewsRelationQuery(null, null, null, null, null, null, 1, 20));

        assertThat(store.lastQuery.relationStatus()).isEqualTo(NewsRelationStatus.CANDIDATE);
    }

    @Test
    void listHonorsAnExplicitStatus() {
        service.list(new AdminNewsRelationQuery(
                NewsRelationStatus.REJECTED, null, null, null, null, null, 1, 20));

        assertThat(store.lastQuery.relationStatus()).isEqualTo(NewsRelationStatus.REJECTED);
    }

    @Test
    void listRejectsAnInvertedTimeRange() {
        AdminNewsRelationQuery query = new AdminNewsRelationQuery(
                null, null, null, null,
                NOW.plusDays(1), NOW, 1, 20);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    @Test
    void listRejectsATooWideTimeRange() {
        AdminNewsRelationQuery query = new AdminNewsRelationQuery(
                null, null, null, null,
                NOW.minusDays(91), NOW, 1, 20);

        assertThatThrownBy(() -> service.list(query))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    // ---------- ADM-NEWS-06：复核 ----------

    @Test
    void reviewRejectsCandidateAsADecision() {
        long relationId = store.insertCandidate(1L, NewsTargetType.SECURITY, 60000000L, 0.5);

        assertThatThrownBy(() -> service.review(
                relationId, NewsRelationStatus.CANDIDATE, null, REVIEWER))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    @Test
    void reviewOfAnAlreadyReviewedRelationIsA409() {
        long relationId = store.insertCandidate(1L, NewsTargetType.SECURITY, 60000000L, 0.5);
        service.review(relationId, NewsRelationStatus.CONFIRMED, null, REVIEWER);

        assertThatThrownBy(() -> service.review(
                relationId, NewsRelationStatus.REJECTED, "改主意了", REVIEWER))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.NEWS_RELATION_ALREADY_REVIEWED));
    }

    @Test
    void reviewConfirmRecordsTheReviewerAndTime() {
        long relationId = store.insertCandidate(1L, NewsTargetType.SECURITY, 60000000L, 0.5);

        AdminNewsRelationView reviewed = service.review(
                relationId, NewsRelationStatus.CONFIRMED, "人工核对是浦发银行", REVIEWER);

        assertThat(reviewed.relationStatus()).isEqualTo(NewsRelationStatus.CONFIRMED);
        assertThat(reviewed.reviewedBy()).isEqualTo(REVIEWER);
        assertThat(reviewed.reviewedAt()).isEqualTo(NOW);
        assertThat(reviewed.reasonSummary()).isEqualTo("人工核对是浦发银行");
    }

    /** 复核没写理由时保留候选关系的原始依据（SQL 侧 COALESCE 的内存等价）。 */
    @Test
    void reviewKeepsTheOriginalReasonWhenNoneIsGiven() {
        long relationId = store.insertCandidate(1L, NewsTargetType.SECURITY, 60000000L, 0.5);

        AdminNewsRelationView reviewed = service.review(
                relationId, NewsRelationStatus.CONFIRMED, null, REVIEWER);

        assertThat(reviewed.reasonSummary()).isEqualTo("标题含证券简称（规则 R3）");
    }

    // ---------- ADM-NEWS-07：手工关联 ----------

    @Test
    void createManualResolvesTheExternalIdAndStoresTheProxyKey() {
        AdminNewsRelationView created = service.createManual(
                1L, NewsTargetType.SECURITY, "sim-600000", "记者核实", REVIEWER);

        assertThat(created.relationMethod()).isEqualTo(NewsRelationMethod.MANUAL);
        assertThat(created.relationStatus()).isEqualTo(NewsRelationStatus.CONFIRMED);
        assertThat(created.confidenceScore()).isNull();
        assertThat(created.reviewedBy()).isEqualTo(REVIEWER);
        // 出网是对外标识，不是代理键。
        assertThat(created.targetId()).isEqualTo("sim-600000");
        assertThat(created.targetCode()).isEqualTo("600000");
        assertThat(created.targetName()).isEqualTo("浦发银行");
        assertThat(store.lastInsertedStorageId).isEqualTo(60000000L);
    }

    @Test
    void createManualRejectsAnUnresolvableTarget() {
        assertThatThrownBy(() -> service.createManual(
                1L, NewsTargetType.SECURITY, "sim-999999", null, REVIEWER))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.NEWS_RELATION_TARGET_INVALID));
    }

    @Test
    void createManualOfAMissingNewsIsA404() {
        assertThatThrownBy(() -> service.createManual(
                4242L, NewsTargetType.SECURITY, "sim-600000", null, REVIEWER))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.NEWS_NOT_FOUND));
    }

    /** 同一新闻 + 同一目标重复提交：契约要求幂等成功，回放已有关联。 */
    @Test
    void createManualIsIdempotentForTheSameTarget() {
        AdminNewsRelationView first = service.createManual(
                1L, NewsTargetType.SECURITY, "sim-600000", null, REVIEWER);

        AdminNewsRelationView second = service.createManual(
                1L, NewsTargetType.SECURITY, "sim-600000", null, REVIEWER);

        assertThat(second.relationId()).isEqualTo(first.relationId());
        assertThat(store.rows).hasSize(1);
    }

    // ---------- ADM-NEWS-08：删除 = 置 REJECTED ----------

    @Test
    void deleteRejectsAConfirmedRelationAndKeepsTheAudit() {
        long relationId = store.insertCandidate(1L, NewsTargetType.SECURITY, 60000000L, 0.8);
        service.review(relationId, NewsRelationStatus.CONFIRMED, null, 9002L);

        AdminNewsRelationView deleted = service.delete(relationId, "关联挂错标的", REVIEWER);

        assertThat(deleted.relationStatus()).isEqualTo(NewsRelationStatus.REJECTED);
        assertThat(deleted.reviewedBy()).isEqualTo(REVIEWER);
        assertThat(deleted.reviewedAt()).isEqualTo(NOW);
    }

    @Test
    void deleteIsIdempotentOnceRejected() {
        long relationId = store.insertCandidate(1L, NewsTargetType.SECURITY, 60000000L, 0.8);
        service.review(relationId, NewsRelationStatus.REJECTED, "第一次拒绝", 9002L);

        AdminNewsRelationView second = service.delete(relationId, "第二次删除", REVIEWER);

        assertThat(second.relationStatus()).isEqualTo(NewsRelationStatus.REJECTED);
        // 幂等重放不覆盖第一次复核的留痕。
        assertThat(second.reviewedBy()).isEqualTo(9002L);
    }

    // ---------- 装配 ----------

    /** 内存仓储：review 模拟 SQL 的 COALESCE（理由为空保留原值）。 */
    private static class InMemoryRelationStore implements AdminNewsRelationStore {

        final Map<Long, AdminNewsRelationEntry> rows = new LinkedHashMap<>();
        final List<Long> manualIds = new ArrayList<>();
        AdminNewsRelationQuery lastQuery;
        Long lastInsertedStorageId;
        private final AtomicLong ids = new AtomicLong(7100);
        private final Map<String, Long> byTargetIndex = new HashMap<>();

        long insertCandidate(
                long newsId, NewsTargetType targetType, long targetId, double confidence) {
            long id = ids.incrementAndGet();
            AdminNewsRelationEntry entry = new AdminNewsRelationEntry(
                    id, newsId, "稿件 " + newsId, targetType, targetId,
                    NewsRelationMethod.RULE, BigDecimal.valueOf(confidence),
                    NewsRelationStatus.CANDIDATE, "标题含证券简称（规则 R3）",
                    null, null, NOW.minusDays(1));
            rows.put(id, entry);
            byTargetIndex.put(targetKey(newsId, targetType, targetId), id);
            return id;
        }

        @Override
        public List<AdminNewsRelationEntry> page(AdminNewsRelationQuery query) {
            this.lastQuery = query;
            return rows.values().stream()
                    .filter(entry -> entry.relationStatus() == query.relationStatus())
                    .toList();
        }

        @Override
        public long count(AdminNewsRelationQuery query) {
            this.lastQuery = query;
            return pageWithoutRecording(query).size();
        }

        private List<AdminNewsRelationEntry> pageWithoutRecording(AdminNewsRelationQuery query) {
            return rows.values().stream()
                    .filter(entry -> entry.relationStatus() == query.relationStatus())
                    .toList();
        }

        @Override
        public Optional<AdminNewsRelationEntry> find(long relationId) {
            return Optional.ofNullable(rows.get(relationId));
        }

        @Override
        public boolean newsExists(long newsId) {
            return newsId != 4242L;
        }

        @Override
        public AdminNewsRelationEntry insertManual(
                long newsId,
                AdminNewsRelationTarget target,
                String reasonSummary,
                long reviewedBy,
                OffsetDateTime reviewedAt) {
            String key = targetKey(newsId, target.targetType(), target.targetId());
            if (byTargetIndex.containsKey(key)) {
                throw new DuplicateKeyException("uk_news_relation_target");
            }
            long id = ids.incrementAndGet();
            AdminNewsRelationEntry entry = new AdminNewsRelationEntry(
                    id, newsId, "稿件 " + newsId, target.targetType(), target.targetId(),
                    NewsRelationMethod.MANUAL, null,
                    NewsRelationStatus.CONFIRMED, reasonSummary,
                    reviewedBy, reviewedAt, NOW);
            rows.put(id, entry);
            byTargetIndex.put(key, id);
            manualIds.add(id);
            lastInsertedStorageId = target.targetId();
            return entry;
        }

        @Override
        public Optional<AdminNewsRelationEntry> findByTarget(
                long newsId, NewsTargetType targetType, long targetId) {
            return Optional.ofNullable(byTargetIndex.get(targetKey(newsId, targetType, targetId)))
                    .map(rows::get);
        }

        @Override
        public boolean review(
                long relationId,
                NewsRelationStatus relationStatus,
                String reasonSummary,
                long reviewedBy,
                OffsetDateTime reviewedAt) {
            AdminNewsRelationEntry current = rows.get(relationId);
            if (current == null) {
                return false;
            }
            rows.put(relationId, new AdminNewsRelationEntry(
                    current.relationId(), current.newsId(), current.newsTitle(),
                    current.targetType(), current.targetId(), current.relationMethod(),
                    current.confidenceScore(), relationStatus,
                    reasonSummary == null ? current.reasonSummary() : reasonSummary,
                    reviewedBy, reviewedAt, current.createdAt()));
            return true;
        }

        private static String targetKey(long newsId, NewsTargetType type, long targetId) {
            return newsId + ":" + type + ":" + targetId;
        }
    }

    private static final class StubSecurityIdentities implements SecurityIdentityProvider {

        private final Map<String, SecurityIdentity> byExternal = new HashMap<>();

        private StubSecurityIdentities() {
            byExternal.put("sim-600000", new SecurityIdentity(60000000L, new SecuritySummary(
                    "sim-600000", "600000.SH", "600000", "浦发银行", "SSE", "STOCK",
                    "MAIN", "LISTED", false, false, 2, null, null)));
        }

        @Override
        public Optional<SecurityIdentity> resolve(String securityId) {
            return Optional.ofNullable(byExternal.get(securityId));
        }

        @Override
        public Map<String, SecurityIdentity> resolveAll(java.util.Collection<String> securityIds) {
            return Map.of();
        }

        @Override
        public Map<Long, SecurityIdentity> findByStorageIds(java.util.Collection<Long> storageIds) {
            Map<Long, SecurityIdentity> result = new HashMap<>();
            byExternal.values().stream()
                    .filter(identity -> storageIds.contains(identity.storageId()))
                    .forEach(identity -> result.put(identity.storageId(), identity));
            return result;
        }
    }

    private static final class StubSectorIdentities implements SectorIdentityProvider {

        @Override
        public Optional<SectorIdentity> resolve(String sectorId) {
            return Optional.empty();
        }

        @Override
        public Map<String, SectorIdentity> resolveAll(java.util.Collection<String> sectorIds) {
            return Map.of();
        }

        @Override
        public Map<Long, SectorIdentity> findByStorageIds(java.util.Collection<Long> storageIds) {
            return Map.of();
        }
    }
}
