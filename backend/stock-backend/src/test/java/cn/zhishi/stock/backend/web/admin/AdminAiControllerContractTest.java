package cn.zhishi.stock.backend.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.admin.application.AdminAiService;
import cn.zhishi.stock.admin.application.AdminException;
import cn.zhishi.stock.admin.domain.AdminAiOverview;
import cn.zhishi.stock.admin.domain.AdminAiTaskDetail;
import cn.zhishi.stock.admin.domain.AdminAiTaskQuery;
import cn.zhishi.stock.admin.domain.AdminAiTaskSummary;
import cn.zhishi.stock.admin.domain.AdminAiUsageGroup;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.GroupBy;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.backend.web.AuditRecorder;
import cn.zhishi.stock.backend.web.GlobalExceptionHandler;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.common.audit.AuditEvent;
import cn.zhishi.stock.common.audit.AuditLog;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyRecord;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * 后台 AI 运营契约（{@code RESTful-API.md} §19 ADM-AI-01~06）。
 *
 * <h2>这里钉的事实</h2>
 * <ul>
 *   <li>路由与查询参数名（{@code groupBy} / {@code startAt} / {@code endAt}）；</li>
 *   <li>取消是 POST + 幂等键，缺键 400；任务详情不存在 404；</li>
 *   <li>取消动作留审计（ADMIN_AI_TASK_CANCEL）。</li>
 * </ul>
 *
 * <p>权限码不在这里测：{@code standaloneSetup} 没有 AOP，由 {@code AdminAuthorizationTest} 核对。
 */
class AdminAiControllerContractTest {

    private static final long OPERATOR_ID = 9001L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());

    private final AdminAiService ai = mock(AdminAiService.class);
    private final RecordingAuditLog auditLog = new RecordingAuditLog();
    private final InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
    private final ObjectMapper objectMapper = mapper();

    @Test
    void overviewReturnsTheAggregateShape() throws Exception {
        when(ai.overview(any(), any())).thenReturn(new AdminAiOverview(
                10, 8, 1, 1, 0, 0.8, 2, 1, 2,
                120L, 300L, 900L, 2_500L,
                1_000, 2_000, 300, 3_000, new java.math.BigDecimal("0"),
                List.of()));

        mvc().perform(get("/api/v1/admin/ai/overview").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskCount").value(10))
                .andExpect(jsonPath("$.data.successRate").value(0.8))
                .andExpect(jsonPath("$.data.restrictedReportCount").value(2))
                .andExpect(jsonPath("$.data.queuedCount").value(1))
                .andExpect(jsonPath("$.data.firstChunkLatencyP50Millis").value(120));
    }

    @Test
    void taskListUsesTheStandardPaginationEnvelope() throws Exception {
        when(ai.listTasks(any())).thenReturn(new PageData<>(
                List.of(new AdminAiTaskSummary(
                        7L, 1L, 2L, "SINGLE_STOCK", AiTaskStatus.RUNNING,
                        "simulated", "sim-model", NOW, NOW, null, null, "trace-1",
                        List.of())),
                1, 20, 1, 1, false));

        mvc().perform(get("/api/v1/admin/ai/tasks").param("status", "RUNNING")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value(7))
                .andExpect(jsonPath("$.data.items[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.total").value(1));

        ArgumentCaptor<AdminAiTaskQuery> captor = ArgumentCaptor.forClass(AdminAiTaskQuery.class);
        verify(ai).listTasks(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(AiTaskStatus.RUNNING);
    }

    @Test
    void taskDetailIs404ForAMissingTask() throws Exception {
        when(ai.taskDetail(4242L)).thenThrow(AdminException.aiTaskNotFound(4242L));

        mvc().perform(get("/api/v1/admin/ai/tasks/4242").principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_ADMIN_TASK_NOT_FOUND"));
    }

    @Test
    void cancelRequiresAnIdempotencyKey() throws Exception {
        mvc().perform(post("/api/v1/admin/ai/tasks/7/cancel")
                        .contentType("application/json")
                        .content("{\"reason\":\"任务卡死\"}")
                        .principal(authentication()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelRecordsTheIntentAndAnAuditTrail() throws Exception {
        when(ai.cancelTask(7L)).thenReturn(detail(AiTaskStatus.RUNNING));

        mvc().perform(post("/api/v1/admin/ai/tasks/7/cancel")
                        .header("Idempotency-Key", "idem-1")
                        .contentType("application/json")
                        .content("{\"reason\":\"任务卡死\"}")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo(AdminAudit.AI_TASK_CANCEL);
            assertThat(event.resultStatus()).isEqualTo(AuditEvent.SUCCESS);
            assertThat(event.paramsSummary()).contains("taskId=7").contains("reason=任务卡死");
        });
    }

    @Test
    void usagePassesTheGroupByDimension() throws Exception {
        when(ai.usage(eq(GroupBy.PROVIDER), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new AdminAiUsageGroup(
                        "simulated", 4, 3, 0.75, 100, 200, 50, 300,
                        new java.math.BigDecimal("0"), 120L, 800L)));

        mvc().perform(get("/api/v1/admin/ai/usage").param("groupBy", "PROVIDER")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].groupKey").value("simulated"))
                .andExpect(jsonPath("$.data[0].calls").value(4));

        verify(ai).usage(eq(GroupBy.PROVIDER), any(), any(), any(), any(), any());
    }

    @Test
    void feedbackStatisticsReturnTheAggregateShape() throws Exception {
        when(ai.feedbackStatistics(any(), any(), any(), any(), any()))
                .thenReturn(new cn.zhishi.stock.admin.domain.AdminAiFeedbackStats(
                        4, 3, 1, 0.75, List.of(), List.of()));

        mvc().perform(get("/api/v1/admin/ai/feedback-statistics").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.helpfulCount").value(3))
                .andExpect(jsonPath("$.data.notHelpfulCount").value(1));
    }

    // ---------- 装配 ----------

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AdminAiController(
                        ai,
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
                OPERATOR_ID, "admin", Set.of("ai:ops:overview"), "jti-1",
                Instant.parse("2030-01-01T00:00:00Z"), 0);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static AdminAiTaskDetail detail(AiTaskStatus status) {
        return new AdminAiTaskDetail(
                7L, 1L, 2L, "SINGLE_STOCK", status, 1, 2, null, false,
                "simulated", "sim-model", NOW, NOW, NOW, null, null, null, null,
                null, null, null, "trace-1", List.of(), List.of(), Map.of());
    }

    private static final class RecordingAuditLog implements AuditLog {

        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    private static final class InMemoryIdempotencyStore implements IdempotencyStore {

        private final Map<String, IdempotencyRecord> records = new java.util.HashMap<>();

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
