package cn.zhishi.stock.backend.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.admin.application.AdminException;
import cn.zhishi.stock.admin.application.AdminNewsRelationService;
import cn.zhishi.stock.admin.application.AdminNewsSourceService;
import cn.zhishi.stock.admin.domain.AdminNewsRelationQuery;
import cn.zhishi.stock.admin.domain.AdminNewsRelationView;
import cn.zhishi.stock.admin.domain.AdminNewsSourcePatch;
import cn.zhishi.stock.admin.domain.AdminNewsSourceQuery;
import cn.zhishi.stock.admin.domain.NewAdminNewsSource;
import cn.zhishi.stock.backend.web.AuditRecorder;
import cn.zhishi.stock.backend.web.GlobalExceptionHandler;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.common.audit.AuditEvent;
import cn.zhishi.stock.common.audit.AuditLog;
import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyRecord;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 后台资讯治理契约（{@code RESTful-API.md} §17.1 ADM-NEWS-01~04 + §17.2 ADM-NEWS-05~08）。
 *
 * <h2>这里钉的事实</h2>
 * <ul>
 *   <li>路由与状态码：创建是 201——来源与关联都是同步落库，不是 202 的异步语义；</li>
 *   <li>错误分支的 HTTP 状态 ↔ 业务码映射稳定（重复编码 409、复核已审关联 409、
 *       详情不存在 404）；</li>
 *   <li>If-Match 缺失时更新直接 400（{@code IfMatch.version} 的既有约定）；</li>
 *   <li>创建与关联的写操作留审计（与任务管理的口径同一套实现）。</li>
 * </ul>
 *
 * <h2>为什么用真实的 {@code IdempotencyGuard} 与 {@code AuditRecorder}</h2>
 * 与 {@code AdminJobControllerContractTest} 同一条理由：这两个组件的行为
 * （回放、指纹冲突、审计失败不影响业务）本身就是契约的一部分，
 * mock 掉它们等于不测。
 *
 * <p>权限码不在这里测：{@code standaloneSetup} 没有 AOP，{@code @PreAuthorize} 不生效；
 * 由 {@code AdminAuthorizationTest} 的表驱动核对。
 */
class AdminNewsControllerContractTest {

    private static final long OPERATOR_ID = 9001L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());

    private final AdminNewsSourceService sources = mock(AdminNewsSourceService.class);
    private final AdminNewsRelationService relations = mock(AdminNewsRelationService.class);
    private final RecordingAuditLog auditLog = new RecordingAuditLog();
    private final InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
    private final ObjectMapper objectMapper = mapper();

    // ---------- ADM-NEWS-01 / 02 ----------

    @Test
    void listsSourcesInTheStandardPaginationEnvelope() throws Exception {
        when(sources.list(any())).thenReturn(new PageData<>(
                List.of(source(11L, "sim-media-a", 0)), 1, 20, 1, 1, false));

        mvc().perform(get("/api/v1/admin/news-sources").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].sourceId").value(11))
                .andExpect(jsonPath("$.data.items[0].sourceCode").value("sim-media-a"))
                .andExpect(jsonPath("$.data.items[0].allowAiAnalysis").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void sourceDetailIs404ForAMissingSource() throws Exception {
        when(sources.detail(4242L)).thenThrow(AdminException.newsSourceNotFound(4242L));

        mvc().perform(get("/api/v1/admin/news-sources/4242").principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NEWS_SOURCE_NOT_FOUND"));
    }

    // ---------- ADM-NEWS-03 ----------

    @Test
    void creatingASourceIs201AndTheDerivedStatusComesBack() throws Exception {
        when(sources.create(any())).thenReturn(source(11L, "sim-media-x", 0));

        mvc().perform(post("/api/v1/admin/news-sources")
                        .header("Idempotency-Key", "idem-1")
                        .contentType("application/json")
                        .content("""
                                {"providerId": null, "sourceCode": "sim-media-x",
                                 "sourceName": "模拟媒体 X", "sourceType": "MEDIA",
                                 "rightsValidFrom": "2026-01-01", "rightsValidTo": "2026-12-31",
                                 "allowAiAnalysis": true, "status": "ACTIVE"}
                                """)
                        .principal(authentication()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sourceCode").value("sim-media-x"))
                .andExpect(jsonPath("$.data.authorizationStatus").value("AUTHORIZED"));

        ArgumentCaptor<NewAdminNewsSource> captor = ArgumentCaptor.forClass(NewAdminNewsSource.class);
        verify(sources).create(captor.capture());
        assertThat(captor.getValue().sourceCode()).isEqualTo("sim-media-x");
        assertThat(captor.getValue().allowAiAnalysis()).isTrue();
    }

    /** 客户端塞进来的 {@code authorizationStatus} 会被 Jackson 忽略：不许伪造有效授权。 */
    @Test
    void creatingASourceIgnoresAClientProvidedAuthorizationStatus() throws Exception {
        when(sources.create(any())).thenReturn(source(11L, "sim-media-x", 0));

        mvc().perform(post("/api/v1/admin/news-sources")
                        .header("Idempotency-Key", "idem-1b")
                        .contentType("application/json")
                        .content("""
                                {"sourceCode": "sim-media-x", "sourceName": "X",
                                 "sourceType": "MEDIA", "authorizationStatus": "AUTHORIZED"}
                                """)
                        .principal(authentication()))
                .andExpect(status().isCreated());

        ArgumentCaptor<NewAdminNewsSource> captor = ArgumentCaptor.forClass(NewAdminNewsSource.class);
        verify(sources).create(captor.capture());
        // 命令类型上没有这个字段：伪造值根本没有进入用例层的通道。
        assertThat(captor.getValue().rightsValidFrom()).isNull();
    }

    @Test
    void creatingASourceWithoutAnIdempotencyKeyIs400() throws Exception {
        mvc().perform(post("/api/v1/admin/news-sources")
                        .contentType("application/json")
                        .content("{\"sourceCode\":\"sim-media-x\",\"sourceName\":\"X\",\"sourceType\":\"MEDIA\"}")
                        .principal(authentication()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingADuplicateSourceCodeIs409() throws Exception {
        when(sources.create(any())).thenThrow(AdminException.newsSourceCodeExists("sim-media-x"));

        mvc().perform(post("/api/v1/admin/news-sources")
                        .header("Idempotency-Key", "idem-2")
                        .contentType("application/json")
                        .content("{\"sourceCode\":\"sim-media-x\",\"sourceName\":\"X\",\"sourceType\":\"MEDIA\"}")
                        .principal(authentication()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NEWS_SOURCE_CODE_EXISTS"));
    }

    // ---------- ADM-NEWS-04 ----------

    @Test
    void updatingASourceWithIfMatchPassesTheExpectedVersion() throws Exception {
        when(sources.update(eq(11L), eq(3), any())).thenReturn(source(11L, "sim-media-a", 4));

        mvc().perform(patch("/api/v1/admin/news-sources/11")
                        .header("If-Match", "3")
                        .contentType("application/json")
                        .content("{\"sourceName\":\"改名后的来源\"}")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4));

        ArgumentCaptor<AdminNewsSourcePatch> captor =
                ArgumentCaptor.forClass(AdminNewsSourcePatch.class);
        verify(sources).update(eq(11L), eq(3), captor.capture());
        assertThat(captor.getValue().sourceName()).isEqualTo(Optional.of("改名后的来源"));
        assertThat(captor.getValue().homepageUrl()).isEmpty();
    }

    @Test
    void updatingASourceWithoutIfMatchIs400() throws Exception {
        mvc().perform(patch("/api/v1/admin/news-sources/11")
                        .contentType("application/json")
                        .content("{\"sourceName\":\"改名\"}")
                        .principal(authentication()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatingAMissingSourceIs404() throws Exception {
        when(sources.update(eq(4242L), eq(1), any()))
                .thenThrow(AdminException.newsSourceNotFound(4242L));

        mvc().perform(patch("/api/v1/admin/news-sources/4242")
                        .header("If-Match", "1")
                        .contentType("application/json")
                        .content("{\"sourceName\":\"不存在\"}")
                        .principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NEWS_SOURCE_NOT_FOUND"));
    }

    // ---------- ADM-NEWS-05 ----------

    @Test
    void relationListUsesTheStandardEnvelopeAndPassesTheStatusThrough() throws Exception {
        when(relations.list(any())).thenReturn(new PageData<>(
                List.of(relation(21L, NewsRelationStatus.CANDIDATE)), 1, 20, 1, 1, false));

        mvc().perform(get("/api/v1/admin/news-relations")
                        .param("relationStatus", "REJECTED")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].relationId").value(21))
                .andExpect(jsonPath("$.data.items[0].newsTitle").value("稿件标题"))
                .andExpect(jsonPath("$.data.items[0].targetId").value("sim-600000"));

        ArgumentCaptor<AdminNewsRelationQuery> captor =
                ArgumentCaptor.forClass(AdminNewsRelationQuery.class);
        verify(relations).list(captor.capture());
        // 缺省 CANDIDATE 是用例层的职责（AdminNewsRelationServiceTest 钉住）；
        // 控制器只负责把显式状态透传过去。
        assertThat(captor.getValue().relationStatus()).isEqualTo(NewsRelationStatus.REJECTED);
    }

    // ---------- ADM-NEWS-06 / 07 / 08 ----------

    @Test
    void reviewingAnAlreadyReviewedRelationIs409() throws Exception {
        when(relations.review(eq(21L), any(), any(), eq(OPERATOR_ID)))
                .thenThrow(AdminException.newsRelationAlreadyReviewed(21L, "CONFIRMED"));

        mvc().perform(patch("/api/v1/admin/news-relations/21")
                        .contentType("application/json")
                        .content("{\"relationStatus\":\"CONFIRMED\"}")
                        .principal(authentication()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NEWS_RELATION_ALREADY_REVIEWED"));
    }

    @Test
    void creatingARelationIs201WithAnExternalTargetId() throws Exception {
        when(relations.createManual(eq(7L), eq(NewsTargetType.SECURITY), eq("sim-600000"),
                any(), eq(OPERATOR_ID)))
                .thenReturn(relation(31L, NewsRelationStatus.CONFIRMED));

        mvc().perform(post("/api/v1/admin/news/7/relations")
                        .header("Idempotency-Key", "idem-3")
                        .contentType("application/json")
                        .content("{\"targetType\":\"SECURITY\",\"targetId\":\"sim-600000\"}")
                        .principal(authentication()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.relationId").value(31))
                .andExpect(jsonPath("$.data.targetId").value("sim-600000"))
                .andExpect(jsonPath("$.data.relationMethod").value("MANUAL"))
                .andExpect(jsonPath("$.data.relationStatus").value("CONFIRMED"));
    }

    @Test
    void deletingARelationReturnsTheRejectedState() throws Exception {
        when(relations.delete(eq(21L), any(), eq(OPERATOR_ID)))
                .thenReturn(relation(21L, NewsRelationStatus.REJECTED));

        mvc().perform(delete("/api/v1/admin/news-relations/21")
                        .contentType("application/json")
                        .content("{\"reasonSummary\":\"关联挂错标的\"}")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewedBy").value(OPERATOR_ID));
    }

    /** 写操作要留审计：创建来源这一笔必须出现在 sys_log 的记录列表里。 */
    @Test
    void creatingASourceLeavesAnAuditTrail() throws Exception {
        when(sources.create(any())).thenReturn(source(11L, "sim-media-audit", 0));

        mvc().perform(post("/api/v1/admin/news-sources")
                        .header("Idempotency-Key", "idem-audit")
                        .contentType("application/json")
                        .content("{\"sourceCode\":\"sim-media-audit\",\"sourceName\":\"A\",\"sourceType\":\"MEDIA\"}")
                        .principal(authentication()))
                .andExpect(status().isCreated());

        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo(AdminAudit.NEWS_SOURCE_CREATE);
            assertThat(event.resultStatus()).isEqualTo(AuditEvent.SUCCESS);
            assertThat(event.userId()).isEqualTo(OPERATOR_ID);
        });
    }

    // ---------- 装配 ----------

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AdminNewsController(
                        sources,
                        relations,
                        new IdempotencyGuard(idempotencyStore, objectMapper),
                        new AuditRecorder(auditLog),
                        CLOCK))
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

    private static UsernamePasswordAuthenticationToken authentication() {
        AccessTokenPrincipal principal = new AccessTokenPrincipal(
                OPERATOR_ID, "admin", Set.of("news:source:list"), "jti-1",
                Instant.parse("2030-01-01T00:00:00Z"), 0);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static NewsSource source(long sourceId, String code, int version) {
        return new NewsSource(
                sourceId, code, "来源 " + code, NewsSourceType.MEDIA,
                null, NewsSource.AuthorizationStatus.AUTHORIZED,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true,
                NewsSource.SourceStatus.ACTIVE, null, null, version);
    }

    private static AdminNewsRelationView relation(long relationId, NewsRelationStatus status) {
        return new AdminNewsRelationView(
                relationId, 7L, "稿件标题", NewsTargetType.SECURITY,
                "sim-600000", "600000", "浦发银行",
                NewsRelationMethod.MANUAL, null, status,
                "人工建立", OPERATOR_ID, NOW, NOW);
    }

    private static final class RecordingAuditLog implements AuditLog {

        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    private static final class InMemoryIdempotencyStore implements IdempotencyStore {

        private final Map<String, IdempotencyRecord> records = new HashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String scope, long userId, String key) {
            return Optional.ofNullable(records.get(scope + '|' + userId + '|' + key));
        }

        @Override
        public void save(String scope, long userId, String key, IdempotencyRecord record) {
            records.put(scope + '|' + userId + '|' + key, record);
        }
    }
}
