package cn.zhishi.stock.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.ai.application.AiCancelResult;
import cn.zhishi.stock.ai.application.AiTaskEventSink;
import cn.zhishi.stock.ai.application.AiQuotaExceededException;
import cn.zhishi.stock.ai.application.AiTaskAccepted;
import cn.zhishi.stock.ai.application.AiTaskCreationRequest;
import cn.zhishi.stock.ai.application.AiTaskErrorCode;
import cn.zhishi.stock.ai.application.AiTaskException;
import cn.zhishi.stock.ai.application.AiTaskQuota;
import cn.zhishi.stock.ai.application.AiTaskService;
import cn.zhishi.stock.ai.application.AiTaskStreamRelay;
import cn.zhishi.stock.ai.application.AiTaskSummary;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * AI 任务接口契约（{@code RESTful-API.md} §13.2 AI-03 / AI-04 / AI-06 / AI-07 / AI-08
 * 与 §13.4 的 SSE 入口）。
 *
 * <h2>这里钉的是 HTTP 面</h2>
 * 路由、请求体绑定、状态码（202 与 200 的区别）、业务码到 HTTP 状态的映射、
 * 幂等键的回放、以及 SSE 的响应头。业务规则（四道闸门、状态机、取消与重试的判据）
 * 由 {@code AiTaskServiceTest} 的 32 项覆盖，在这里重造一套只会得到两份会各自漂移的夹具。
 *
 * <h2>为什么用真实的 {@code IdempotencyGuard}</h2>
 * 契约 §3.7 要的是"重复提交返回第一次的结果"，而这条**只能**用真实的 guard 加一个
 * 内存 store 才能验：把 guard 桩掉，就等于把"同一个键回放同一个 taskId"换成
 * "我写了一句 thenReturn"。顺带钉住"用例层只被调用一次"。
 */
class AiTaskControllerContractTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 20, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    private static final long USER_ID = 1001L;
    private static final String TASK_ID = "19876543219999";

    private final AiTaskService tasks = mock(AiTaskService.class);
    private final InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();

    // ---------- AI-03 ----------

    @Test
    @DisplayName("AI-03 创建任务返回 202，含 taskId / streamUrl / quota")
    void createReturnsAccepted() throws Exception {
        when(tasks.create(any(), anyLong(), anyString(), anyString())).thenReturn(accepted());

        mvc().perform(post("/api/v1/ai/tasks")
                        .principal(authentication())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.task.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.task.progressStage").value("排队中"))
                .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/ai/tasks/" + TASK_ID))
                .andExpect(jsonPath("$.data.streamUrl")
                        .value("/api/v1/ai/tasks/" + TASK_ID + "/stream"))
                .andExpect(jsonPath("$.data.quota.dailyLimit").value(20))
                .andExpect(jsonPath("$.data.quota.usedCount").value(3))
                .andExpect(jsonPath("$.data.quota.resetsAt").value("2026-09-21T00:00:00+08:00"));
    }

    /** 契约 §3.7：同一个 {@code Idempotency-Key} 重发必须返回同一个 taskId，且不重复消耗额度。 */
    @Test
    @DisplayName("AI-03 同一个 Idempotency-Key 重发：同一个 taskId，用例层只跑一次")
    void createReplaysForTheSameIdempotencyKey() throws Exception {
        when(tasks.create(any(), anyLong(), anyString(), anyString())).thenReturn(accepted());

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc().perform(post("/api/v1/ai/tasks")
                            .principal(authentication())
                            .header("Idempotency-Key", "same-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.task.taskId").value(TASK_ID));
        }

        verify(tasks, times(1)).create(any(), anyLong(), anyString(), anyString());
    }

    /** 契约 §13.2 逐个写明了 {@code Idempotency-Key}，缺它必须是 400 而不是"悄悄放行"。 */
    @Test
    @DisplayName("AI-03 缺 Idempotency-Key → 400，且不创建任务")
    void createWithoutIdempotencyKeyIsRejected() throws Exception {
        mvc().perform(post("/api/v1/ai/tasks")
                        .principal(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(tasks, never()).create(any(), anyLong(), anyString(), anyString());
    }

    /** 请求体必须原样交给用例层：字段名写错时接口不报错，只会静默用默认值。 */
    @Test
    @DisplayName("AI-03 请求体原样透传给用例层")
    void passesCreateBodyThrough() throws Exception {
        when(tasks.create(any(), anyLong(), anyString(), anyString())).thenReturn(accepted());

        mvc().perform(post("/api/v1/ai/tasks")
                        .principal(authentication())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isAccepted());

        org.mockito.ArgumentCaptor<AiTaskCreationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(AiTaskCreationRequest.class);
        verify(tasks).create(captor.capture(), eq(USER_ID), eq("key-1"), anyString());
        AiTaskCreationRequest captured = captor.getValue();
        assertThat(captured.scene()).isEqualTo("STOCK");
        assertThat(captured.sessionId()).isNull();
        assertThat(captured.question()).contains("怎么看");
        assertThat(captured.targets()).hasSize(1);
        assertThat(captured.targets().get(0).targetId()).isEqualTo("sim-600519");
        assertThat(captured.analysisEndAt())
                .isEqualTo(OffsetDateTime.of(2026, 9, 18, 15, 0, 0, 0, ZoneOffset.ofHours(8)));
    }

    // ---------- AI-04 ----------

    @Test
    @DisplayName("AI-04 查询任务：完成时带 reportId，运行时 reportId 为 null")
    void getReturnsTaskSummary() throws Exception {
        when(tasks.get(anyLong(), eq(USER_ID)))
                .thenReturn(summary(AiTaskStatus.COMPLETED, "77001"));

        mvc().perform(get("/api/v1/ai/tasks/{taskId}", TASK_ID).principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reportId").value("77001"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
    }

    /**
     * 别人的任务与不存在的任务返回**同一句** 404。
     *
     * <p>归属校验在用例层，控制器不额外判断——多一层判断就会有两条措辞，
     * 而措辞差异会泄露"这个 ID 存在"（契约 §23.1）。
     */
    @Test
    @DisplayName("AI-04 他人任务或不存在的任务 → 404 AI_TASK_NOT_FOUND")
    void getMapsMissingTaskTo404() throws Exception {
        when(tasks.get(anyLong(), eq(USER_ID)))
                .thenThrow(AiTaskException.taskNotFound(Long.parseLong(TASK_ID)));

        mvc().perform(get("/api/v1/ai/tasks/{taskId}", TASK_ID).principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AI_TASK_NOT_FOUND"));
    }

    // ---------- AI-06 ----------

    @Test
    @DisplayName("AI-06 取消：置上意图并返回 effectiveImmediately=true")
    void cancelReturnsEffectiveImmediately() throws Exception {
        when(tasks.cancel(anyLong(), eq(USER_ID)))
                .thenReturn(new AiCancelResult(TASK_ID, AiTaskStatus.RUNNING, true, true));

        mvc().perform(post("/api/v1/ai/tasks/{taskId}/cancel", TASK_ID)
                        .principal(authentication())
                        .header("Idempotency-Key", "cancel-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.cancelRequested").value(true))
                .andExpect(jsonPath("$.data.effectiveImmediately").value(true));
    }

    /**
     * 契约 AI-06：已完成的任务保持 {@code COMPLETED}，只把 {@code effectiveImmediately} 置为 false。
     *
     * <p>把状态改成 {@code CANCELED} 会让用户丢掉一份已经生成的报告。
     */
    @Test
    @DisplayName("AI-06 取消已完成任务：状态不变，effectiveImmediately=false")
    void cancelOfCompletedTaskKeepsStatus() throws Exception {
        when(tasks.cancel(anyLong(), eq(USER_ID)))
                .thenReturn(new AiCancelResult(TASK_ID, AiTaskStatus.COMPLETED, false, false));

        mvc().perform(post("/api/v1/ai/tasks/{taskId}/cancel", TASK_ID)
                        .principal(authentication())
                        .header("Idempotency-Key", "cancel-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.cancelRequested").value(false))
                .andExpect(jsonPath("$.data.effectiveImmediately").value(false));
    }

    // ---------- AI-07 ----------

    @Test
    @DisplayName("AI-07 重试返回 202，新任务的 retryOfTaskId 指向原任务")
    void retryReturnsAccepted() throws Exception {
        when(tasks.retry(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(accepted());

        mvc().perform(post("/api/v1/ai/tasks/{taskId}/retry", TASK_ID)
                        .principal(authentication())
                        .header("Idempotency-Key", "retry-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"再分析一次\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.task.taskId").value(TASK_ID));

        verify(tasks).retry(
                eq(Long.parseLong(TASK_ID)), eq(USER_ID), eq("retry-1"), eq("再分析一次"), anyString());
    }

    /** 只有 {@code FAILED} / {@code TIMED_OUT} 可重试；其它状态是 409 而不是 400。 */
    @Test
    @DisplayName("AI-07 不可重试的状态 → 409 AI_TASK_NOT_RETRYABLE")
    void retryMapsNotRetryableTo409() throws Exception {
        when(tasks.retry(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenThrow(new AiTaskException(
                        AiTaskErrorCode.TASK_NOT_RETRYABLE, "只有失败或超时的任务可以重试"));

        mvc().perform(post("/api/v1/ai/tasks/{taskId}/retry", TASK_ID)
                        .principal(authentication())
                        .header("Idempotency-Key", "retry-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_TASK_NOT_RETRYABLE"));
    }

    /** 契约 AI-07 的请求体是可选的。 */
    @Test
    @DisplayName("AI-07 不带请求体也能重试（沿用原问题）")
    void retryWorksWithoutBody() throws Exception {
        when(tasks.retry(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(accepted());

        mvc().perform(post("/api/v1/ai/tasks/{taskId}/retry", TASK_ID)
                        .principal(authentication())
                        .header("Idempotency-Key", "retry-3"))
                .andExpect(status().isAccepted());

        verify(tasks).retry(
                eq(Long.parseLong(TASK_ID)), eq(USER_ID), eq("retry-3"), eq(null), anyString());
    }

    // ---------- AI-08 ----------

    @Test
    @DisplayName("AI-08 追问返回 202")
    void followUpReturnsAccepted() throws Exception {
        when(tasks.followUp(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(accepted());

        mvc().perform(post("/api/v1/ai/sessions/{sessionId}/follow-up-tasks", 5001L)
                        .principal(authentication())
                        .header("Idempotency-Key", "follow-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"那风险呢？\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.task.taskId").value(TASK_ID));
    }

    @Test
    @DisplayName("AI-08 会话已只读 → 409 AI_SESSION_READ_ONLY")
    void followUpMapsReadOnlySessionTo409() throws Exception {
        when(tasks.followUp(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenThrow(new AiTaskException(
                        AiTaskErrorCode.SESSION_READ_ONLY, "会话已不是活动状态"));

        mvc().perform(post("/api/v1/ai/sessions/{sessionId}/follow-up-tasks", 5001L)
                        .principal(authentication())
                        .header("Idempotency-Key", "follow-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"那风险呢？\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_SESSION_READ_ONLY"));
    }

    // ---------- 三个闸门的业务码 ----------

    /** 额度用尽：429，且响应体带 quota（前端据此显示"何时恢复"）。 */
    @Test
    @DisplayName("额度用尽 → 429 AI_QUOTA_EXCEEDED，且带 quota")
    void mapsQuotaExceededTo429() throws Exception {
        AiTaskQuota quota = AiTaskQuota.of(
                20, 20, 0, 2, OffsetDateTime.of(2026, 9, 21, 0, 0, 0, 0, ZoneOffset.ofHours(8)));
        when(tasks.create(any(), anyLong(), anyString(), anyString()))
                .thenThrow(new AiQuotaExceededException(quota));

        mvc().perform(post("/api/v1/ai/tasks")
                        .principal(authentication())
                        .header("Idempotency-Key", "quota-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.data.dailyLimit").value(20))
                .andExpect(jsonPath("$.data.usedCount").value(20))
                .andExpect(jsonPath("$.data.resetsAt").value("2026-09-21T00:00:00+08:00"));
    }

    @Test
    @DisplayName("并发超限 → 429 AI_CONCURRENCY_EXCEEDED")
    void mapsConcurrencyExceededTo429() throws Exception {
        when(tasks.create(any(), anyLong(), anyString(), anyString()))
                .thenThrow(new AiTaskException(
                        AiTaskErrorCode.CONCURRENCY_EXCEEDED, "单用户最多 2 个进行中的 AI 任务"));

        mvc().perform(post("/api/v1/ai/tasks")
                        .principal(authentication())
                        .header("Idempotency-Key", "concurrent-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_CONCURRENCY_EXCEEDED"));
    }

    /** 核心行情缺失：503 而不是 400——这是"上游数据不可用"，不是"请求写错了"。 */
    @Test
    @DisplayName("核心行情缺失 → 503 AI_CORE_DATA_MISSING")
    void mapsCoreDataMissingTo503() throws Exception {
        when(tasks.create(any(), anyLong(), anyString(), anyString()))
                .thenThrow(new AiTaskException(
                        AiTaskErrorCode.CORE_DATA_MISSING, "核心行情暂不可用，无法创建分析任务"));

        mvc().perform(post("/api/v1/ai/tasks")
                        .principal(authentication())
                        .header("Idempotency-Key", "core-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_CORE_DATA_MISSING"));
    }

    // ---------- AI-05 ----------

    /**
     * SSE 入口：响应头必须是 {@code text/event-stream} 且进入异步。
     *
     * <p>中继本身是桩（它的循环逻辑由 {@code AiTaskStreamRelayTest} 覆盖），
     * 执行器传 {@code Runnable::run} 让它同步跑完——桩什么都不做，于是这里
     * 只验"路由 + 响应头 + 归属校验发生在建流之前"这三件事。
     */
    @Test
    @DisplayName("AI-05 SSE：响应头 text/event-stream 并进入异步")
    void streamStartsSseResponse() throws Exception {
        when(tasks.get(anyLong(), eq(USER_ID))).thenReturn(summary(AiTaskStatus.RUNNING, null));
        AiTaskStreamRelay relay = mock(AiTaskStreamRelay.class);
        // 让中继真的写一条 snapshot：SSE 的 Content-Type 是**第一次写**的时候才落到响应上的，
        // 只把中继桩成"什么都不做"就断言不到它——而这条响应头正是契约 §13.2
        // 对 AI-05 唯一的硬性要求。
        doAnswer(invocation -> {
            AiTaskEventSink sink = invocation.getArgument(2);
            sink.sendSnapshot("{\"lastSequence\":0}");
            return null;
        }).when(relay).relay(any(), anyLong(), any());

        mvc(relay).perform(get("/api/v1/ai/tasks/{taskId}/stream", TASK_ID)
                        .principal(authentication())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/event-stream")))
                .andExpect(request().asyncStarted());
    }

    /**
     * 归属校验必须在建流**之前**完成。
     *
     * <p>SSE 一旦开始，响应头就发出去了，之后再无法表达 404；前端只会看到一个空流，
     * 而它无从区分"没有权限"和"任务还没产生事件"。
     */
    @Test
    @DisplayName("AI-05 他人任务 → 404（普通 JSON 错误，不是空流）")
    void streamMapsMissingTaskTo404() throws Exception {
        when(tasks.get(anyLong(), eq(USER_ID)))
                .thenThrow(AiTaskException.taskNotFound(Long.parseLong(TASK_ID)));

        mvc().perform(get("/api/v1/ai/tasks/{taskId}/stream", TASK_ID)
                        .principal(authentication())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_TASK_NOT_FOUND"));
    }

    // ---------- 夹具 ----------

    private static String createBody() {
        return "{\"sessionId\":null,\"scene\":\"STOCK\",\"targets\":["
                + "{\"targetType\":\"SECURITY\",\"targetId\":\"sim-600519\","
                + "\"targetRole\":\"PRIMARY\"}],"
                + "\"analysisStartAt\":\"2026-09-01T00:00:00+08:00\","
                + "\"analysisEndAt\":\"2026-09-18T15:00:00+08:00\","
                + "\"question\":\"怎么看\"}";
    }

    private static AiTaskAccepted accepted() {
        AiTaskQuota quota = AiTaskQuota.of(
                20, 3, 1, 2, OffsetDateTime.of(2026, 9, 21, 0, 0, 0, 0, ZoneOffset.ofHours(8)));
        return new AiTaskAccepted(
                summary(AiTaskStatus.QUEUED, null),
                "/api/v1/ai/tasks/" + TASK_ID,
                "/api/v1/ai/tasks/" + TASK_ID + "/stream",
                quota);
    }

    private static AiTaskSummary summary(AiTaskStatus status, String reportId) {
        return new AiTaskSummary(
                TASK_ID,
                "5001",
                AiScene.STOCK,
                status,
                List.of(),
                "怎么看",
                status.progressStage(),
                NOW,
                null,
                status.terminal() ? NOW : null,
                reportId,
                null);
    }

    private MockMvc mvc() {
        return mvc(mock(AiTaskStreamRelay.class));
    }

    private MockMvc mvc(AiTaskStreamRelay relay) {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        IdempotencyGuard guard = new IdempotencyGuard(idempotencyStore, mapper);
        return MockMvcBuilders.standaloneSetup(
                        new AiTaskController(tasks, guard, CLOCK),
                        new AiTaskStreamController(tasks, relay, Runnable::run, 120))
                .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        var principal = new AccessTokenPrincipal(
                USER_ID, "demo", Set.of("ai:read"), "jti-1", Instant.parse("2026-09-20T07:00:00Z"));
        return new UsernamePasswordAuthenticationToken(principal, "token", Set.of());
    }

    /** 内存幂等存储：契约测试只需要"同一个键回放同一个响应"。 */
    private static final class InMemoryIdempotencyStore implements IdempotencyStore {

        private final Map<String, IdempotencyRecord> records = new LinkedHashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String scope, long userId, String key) {
            return Optional.ofNullable(records.get(scope + "|" + userId + "|" + key));
        }

        @Override
        public void save(String scope, long userId, String key, IdempotencyRecord record) {
            records.put(scope + "|" + userId + "|" + key, record);
        }
    }
}
