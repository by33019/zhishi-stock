package cn.zhishi.stock.backend.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.admin.application.AdminException;
import cn.zhishi.stock.admin.application.JobAdminService;
import cn.zhishi.stock.admin.application.RetryJobCommand;
import cn.zhishi.stock.admin.application.TriggerJobCommand;
import cn.zhishi.stock.admin.domain.JobDefinitionCatalog;
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
import cn.zhishi.stock.system.job.JobExecution;
import cn.zhishi.stock.system.job.JobExecutionCounts;
import cn.zhishi.stock.system.job.JobExecutionQuery;
import cn.zhishi.stock.system.job.JobExecutionStatus;
import cn.zhishi.stock.system.job.JobNames;
import cn.zhishi.stock.system.job.JobTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 后台定时任务接口契约（{@code RESTful-API.md} §16.3 ADM-JOB-01~05）。
 *
 * <h2>这一层钉的是 HTTP 面上的事实</h2>
 * <ul>
 *   <li>{@code 202} 是真异步：响应体里那条记录还是 {@code RUNNING}；</li>
 *   <li>缺 {@code Idempotency-Key} 是 400，而不是"当成没有幂等保护"
 *       （后者在 Redis 里会退化成所有请求共用一个 {@code ...:null} 桶）；</li>
 *   <li>同一个键 + 同一个请求体只真正触发一次；</li>
 *   <li>非法 {@code status}/{@code triggerType} 在参数解析阶段就 400，不会静默变成"零条结果"；</li>
 *   <li>业务码到 HTTP 状态的映射（404 / 409）；</li>
 *   <li><b>计数未采集与"处理了 0 条"在响应里必须能分辨</b>——前者是 {@code counts: null}。</li>
 * </ul>
 *
 * <h2>为什么用真实的 {@code IdempotencyGuard} 与 {@code AuditRecorder}</h2>
 * "重复点击不会跑两次"和"缺键是 400"都发生在守卫内部，mock 掉守卫就只能断言
 * "守卫被调用过"，而那正是这两条契约要证明的东西。审计同理：摘要里到底写了什么，
 * 只有让它真的跑一遍才知道。两者都只依赖一个内存实现，不需要容器。
 *
 * <h2>权限码不在这里测</h2>
 * {@code standaloneSetup} 没有 AOP 基础设施，{@code @PreAuthorize} 不会生效。
 * 端点到权限码的对应关系由 {@code AdminAuthorizationTest} 逐行核对。
 */
class AdminJobControllerContractTest {

    private static final long OPERATOR_ID = 9001L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final String TRIGGER_PATH =
            "/api/v1/admin/job-definitions/" + JobNames.MARKET_OVERVIEW_COLLECT + "/executions";

    private static final String TRIGGER_BODY = """
            {"scopeKey":"cn","providerId":7,"shardTotal":2,"reason":"排查采集延迟"}
            """;

    private final JobAdminService jobs = mock(JobAdminService.class);
    private final RecordingAuditLog auditLog = new RecordingAuditLog();
    private final InMemoryIdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
    private final ObjectMapper objectMapper = mapper();

    @BeforeEach
    void defaultStubs() {
        when(jobs.definitions())
                .thenReturn(new JobDefinitionCatalog(60_000, 120_000, 600_000).all());
        when(jobs.trigger(any(), any(), anyLong(), any())).thenReturn(running(1001L, 1));
        when(jobs.retry(anyLong(), any(), anyLong(), any())).thenReturn(retried(2002L));
        when(jobs.list(any())).thenReturn(new PageData<>(List.of(running(1001L, 1)), 1, 20, 1, 1, false));
        when(jobs.detail(anyLong())).thenReturn(failed(1001L));
    }

    // ---------- ADM-JOB-01 ----------

    @Test
    void definitionsExposeTheWhitelistWithTheirRealFixedDelay() throws Exception {
        mvc().perform(get("/api/v1/admin/job-definitions").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].jobName").value(JobNames.MARKET_OVERVIEW_COLLECT))
                .andExpect(jsonPath("$.data[0].displayName").value("行情总览采集"))
                .andExpect(jsonPath("$.data[0].handlerName").value(JobNames.MARKET_OVERVIEW_HANDLER))
                .andExpect(jsonPath("$.data[0].scheduleDescription").value("每 60 秒（fixedDelay）"))
                .andExpect(jsonPath("$.data[0].supportsManualTrigger").value(true))
                .andExpect(jsonPath("$.data[0].supportsShard").value(false))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].scopeKeyLabel").value("市场代码"))
                .andExpect(jsonPath("$.data[0].allowedScopeKeys[0]").value("CN"))
                .andExpect(jsonPath("$.data[0].defaultScopeKey").value("CN"))
                .andExpect(jsonPath("$.data[1].jobName").value(JobNames.NEWS_INGEST))
                .andExpect(jsonPath("$.data[1].scheduleDescription").value("每 120 秒（fixedDelay）"))
                .andExpect(jsonPath("$.data[2].scheduleDescription").value("每 600 秒（fixedDelay）"));
    }

    /**
     * 不接受作用范围的任务，{@code scopeKeyLabel} 必须是 null。
     *
     * <p>给资讯采集编一个"来源"标签会让界面渲染出一个填了也不会生效的输入框。
     */
    @Test
    void definitionsLeaveTheScopeLabelEmptyForTasksThatDoNotAcceptOne() throws Exception {
        mvc().perform(get("/api/v1/admin/job-definitions").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].scopeKeyLabel").value(nullValue()))
                .andExpect(jsonPath("$.data[1].allowedScopeKeys.length()").value(0))
                .andExpect(jsonPath("$.data[1].defaultScopeKey").value(nullValue()));
    }

    // ---------- ADM-JOB-02 ----------

    @Test
    void triggerAnswers202WithTheRecordThatIsStillRunning() throws Exception {
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionId").value(1001))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.data.attemptNo").value(1))
                .andExpect(jsonPath("$.data.jobName").value(JobNames.MARKET_OVERVIEW_COLLECT))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.completedAt").value(nullValue()));
    }

    /**
     * 计数未采集时 {@code counts} 是 null，而不是五个 0。
     *
     * <p>"跑了但一条都没处理"与"这次没记计数"是两件事。若把后者写成 0，
     * 页面会渲染出一排 0，运维据此判断"采集坏了"，而真相是任务没报计数。
     */
    @Test
    void triggerReportsNoCountsRatherThanZeroes() throws Exception {
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.counts").value(nullValue()));
    }

    @Test
    void requiresIdempotencyKeyOnTrigger() throws Exception {
        mvc().perform(post(TRIGGER_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRIGGER_BODY)
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(jobs, times(0)).trigger(any(), any(), anyLong(), any());
    }

    @Test
    void replaysTheTriggerWithoutRunningItTwice() throws Exception {
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY)).andExpect(status().isAccepted());
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY)).andExpect(status().isAccepted());

        verify(jobs, times(1)).trigger(any(), any(), anyLong(), any());
    }

    /** 不同的键是两件独立的事——否则"再点一次"永远无效。 */
    @Test
    void treatsAFreshKeyAsANewTrigger() throws Exception {
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY)).andExpect(status().isAccepted());
        mvc().perform(triggerRequest("key-2", TRIGGER_BODY)).andExpect(status().isAccepted());

        verify(jobs, times(2)).trigger(any(), any(), anyLong(), any());
    }

    /**
     * 同一个键打到两个不同任务上必须是冲突，而不是回放第一个的结果。
     *
     * <p>任务名在**路径**上，不在请求体里。指纹里少了它，两次调用（请求体都为空）
     * 会被判成同一次请求：第二次返回 202、库里没有任何新记录、审计还记了一次成功。
     * 这是最难发现的一类故障——落库的 MANUAL 行只少了一行，而页面上一切正常。
     */
    @Test
    void treatsTheSameKeyOnADifferentJobAsAConflict() throws Exception {
        mvc().perform(triggerRequestFor(JobNames.MARKET_OVERVIEW_COLLECT, "shared-key", null))
                .andExpect(status().isAccepted());

        mvc().perform(triggerRequestFor(JobNames.NEWS_INGEST, "shared-key", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        verify(jobs, times(1)).trigger(any(), any(), anyLong(), any());
    }

    /** 请求体整个可以不传（契约里字段全为可选）。 */
    @Test
    void acceptsATriggerWithoutABody() throws Exception {
        mvc().perform(post(TRIGGER_PATH)
                        .header("Idempotency-Key", "key-1")
                        .principal(authentication()))
                .andExpect(status().isAccepted());

        ArgumentCaptor<TriggerJobCommand> captor = ArgumentCaptor.forClass(TriggerJobCommand.class);
        verify(jobs).trigger(any(), captor.capture(), anyLong(), any());
        TriggerJobCommand command = captor.getValue();
        assertThat(command.scopeKey()).isNull();
        assertThat(command.providerId()).isNull();
        assertThat(command.shardTotal()).isNull();
        assertThat(command.reason()).isNull();
    }

    @Test
    void passesScopeProviderShardAndReasonToTheService() throws Exception {
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY)).andExpect(status().isAccepted());

        ArgumentCaptor<TriggerJobCommand> captor = ArgumentCaptor.forClass(TriggerJobCommand.class);
        verify(jobs).trigger(any(), captor.capture(), anyLong(), any());
        TriggerJobCommand command = captor.getValue();
        assertThat(command.scopeKey()).isEqualTo("cn");
        assertThat(command.providerId()).isEqualTo(7L);
        assertThat(command.shardTotal()).isEqualTo(2);
        assertThat(command.reason()).isEqualTo("排查采集延迟");
    }

    /** 触发者取自令牌，不取自请求体——否则任何人都能以别人的名义写审计。 */
    @Test
    void takesTheOperatorFromTheTokenRatherThanTheBody() throws Exception {
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY)).andExpect(status().isAccepted());

        verify(jobs).trigger(any(), any(), org.mockito.ArgumentMatchers.eq(OPERATOR_ID), any());
    }

    @Test
    void mapsAnUnknownJobTo404AndAuditsItAsDenied() throws Exception {
        when(jobs.trigger(any(), any(), anyLong(), any()))
                .thenThrow(AdminException.jobNotFound("drop-tables"));

        mvc().perform(post("/api/v1/admin/job-definitions/drop-tables/executions")
                        .header("Idempotency-Key", "key-1")
                        .principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADMIN_JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("drop-tables")));

        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo("ADMIN_JOB_TRIGGER");
            assertThat(event.resultStatus())
                    .describedAs("白名单之外的名字是越权尝试，不是普通失败")
                    .isEqualTo(AuditEvent.DENIED);
        });
    }

    @Test
    void mapsANonTriggerableJobTo409() throws Exception {
        when(jobs.trigger(any(), any(), anyLong(), any()))
                .thenThrow(AdminException.jobNotTriggerable(JobNames.NEWS_INGEST, "任务已停用"));

        mvc().perform(triggerRequest("key-1", TRIGGER_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_JOB_NOT_TRIGGERABLE"))
                .andExpect(jsonPath("$.message").value(containsString("任务已停用")));

        assertThat(auditLog.events).singleElement().satisfies(event ->
                assertThat(event.resultStatus())
                        .describedAs("任务存在但不可触发是普通失败：服务没有拒绝谁，只是当前不让做")
                        .isEqualTo(AuditEvent.FAILURE));
    }

    @Test
    void recordsTheTriggeredJobAndReasonInTheAuditSummary() throws Exception {
        mvc().perform(triggerRequest("key-1", TRIGGER_BODY)).andExpect(status().isAccepted());

        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo("ADMIN_JOB_TRIGGER");
            assertThat(event.paramsSummary())
                    .isEqualTo("jobName=" + JobNames.MARKET_OVERVIEW_COLLECT
                            + ";scopeKey=cn;reason=排查采集延迟");
            assertThat(event.resultStatus()).isEqualTo(AuditEvent.SUCCESS);
            assertThat(event.userId()).isEqualTo(OPERATOR_ID);
            assertThat(event.traceId()).isNotBlank();
        });
    }

    /**
     * 没给原因时摘要里不能出现 {@code reason=null}。
     *
     * <p>{@code "reason=" + text(null)} 会拼出字面量 {@code null}（Java 的字符串拼接
     * 不做判空），于是每一条日志都有一个 {@code reason} 字段。运维按它检索时
     * 分不清"没填原因"和"原因是 null 这个词"。
     */
    @Test
    void omitsTheReasonFromTheAuditWhenItWasNotGiven() throws Exception {
        mvc().perform(post(TRIGGER_PATH)
                        .header("Idempotency-Key", "key-1")
                        .principal(authentication()))
                .andExpect(status().isAccepted());

        assertThat(auditLog.events).singleElement().satisfies(event ->
                assertThat(event.paramsSummary())
                        .isEqualTo("jobName=" + JobNames.MARKET_OVERVIEW_COLLECT + ";scopeKey=-"));
    }

    // ---------- ADM-JOB-03 ----------

    @Test
    void returnsTheStandardPaginationEnvelope() throws Exception {
        when(jobs.list(any())).thenReturn(new PageData<>(
                List.of(running(1001L, 1), failed(1002L)), 2, 20, 42, 3, true));

        mvc().perform(get("/api/v1/admin/job-executions").param("page", "2")
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].executionId").value(1001))
                .andExpect(jsonPath("$.data.items[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.items[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[1].counts.inputCount").value(10))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    void parsesEveryFilterIntoTheQuery() throws Exception {
        mvc().perform(get("/api/v1/admin/job-executions")
                        .param("jobName", JobNames.NEWS_INGEST)
                        .param("providerId", "7")
                        .param("status", "PARTIAL")
                        .param("triggerType", "RETRY")
                        .param("batchId", "batch-1")
                        .param("startedAt", "2026-09-01T00:00:00+08:00")
                        .param("endedAt", "2026-09-02T00:00:00+08:00")
                        .param("page", "3")
                        .param("size", "50")
                        .principal(authentication()))
                .andExpect(status().isOk());

        ArgumentCaptor<JobExecutionQuery> captor = ArgumentCaptor.forClass(JobExecutionQuery.class);
        verify(jobs).list(captor.capture());
        JobExecutionQuery query = captor.getValue();
        assertThat(query.jobName()).isEqualTo(JobNames.NEWS_INGEST);
        assertThat(query.providerId()).isEqualTo(7L);
        assertThat(query.status()).isEqualTo(JobExecutionStatus.PARTIAL);
        assertThat(query.triggerType()).isEqualTo(JobTriggerType.RETRY);
        assertThat(query.batchId()).isEqualTo("batch-1");
        // 带偏移量的 ISO-8601 必须被原样保留，而不是被解释成服务器本地时间。
        assertThat(query.startedAt()).isEqualTo(OffsetDateTime.parse("2026-09-01T00:00:00+08:00"));
        assertThat(query.endedAt()).isEqualTo(OffsetDateTime.parse("2026-09-02T00:00:00+08:00"));
        assertThat(query.page()).isEqualTo(3);
        assertThat(query.size()).isEqualTo(50);
    }

    @Test
    void defaultsToTheFirstPageOfTwentyWithNoFilters() throws Exception {
        mvc().perform(get("/api/v1/admin/job-executions").principal(authentication()))
                .andExpect(status().isOk());

        ArgumentCaptor<JobExecutionQuery> captor = ArgumentCaptor.forClass(JobExecutionQuery.class);
        verify(jobs).list(captor.capture());
        JobExecutionQuery query = captor.getValue();
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.jobName()).isNull();
        assertThat(query.startedAt())
                .describedAs("时间缺省由用例层补，控制器不替它猜一个值")
                .isNull();
    }

    /**
     * 非法枚举必须在参数解析阶段报错。
     *
     * <p>若 {@code status} 接收 {@code String}，拼错的取值会一路传到 SQL，
     * 然后以"零条结果"的形式返回——一个不会报错、只会骗人的答案。
     */
    @Test
    void rejectsAnUnknownStatusInsteadOfIgnoringIt() throws Exception {
        mvc().perform(get("/api/v1/admin/job-executions").param("status", "SUCCEEDED")
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(jobs, times(0)).list(any());
    }

    @Test
    void rejectsAnUnknownTriggerTypeInsteadOfIgnoringIt() throws Exception {
        mvc().perform(get("/api/v1/admin/job-executions").param("triggerType", "CRON")
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(jobs, times(0)).list(any());
    }

    @Test
    void mapsATooWideRangeTo400() throws Exception {
        when(jobs.list(any()))
                .thenThrow(AdminException.invalidRequest("任务执行记录的查询跨度不能超过 90 天"));

        mvc().perform(get("/api/v1/admin/job-executions").principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("90")));
    }

    // ---------- ADM-JOB-04 ----------

    @Test
    void returnsTheFailedDetailWithItsCountsAndError() throws Exception {
        mvc().perform(get("/api/v1/admin/job-executions/{id}", 1001L)
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionId").value(1001))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.counts.inputCount").value(10))
                .andExpect(jsonPath("$.data.counts.successCount").value(7))
                .andExpect(jsonPath("$.data.counts.ignoredCount").value(1))
                .andExpect(jsonPath("$.data.counts.failureCount").value(2))
                .andExpect(jsonPath("$.data.counts.outputCount").value(7))
                .andExpect(jsonPath("$.data.errorCategory").value("PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.data.errorCode").value("NEWS_SOURCE_TIMEOUT"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
    }

    @Test
    void mapsAnUnknownExecutionTo404() throws Exception {
        when(jobs.detail(anyLong()))
                .thenThrow(AdminException.jobExecutionNotFound(404L));

        mvc().perform(get("/api/v1/admin/job-executions/{id}", 404L).principal(authentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADMIN_JOB_EXECUTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("404")));
    }

    // ---------- ADM-JOB-05 ----------

    @Test
    void retryAnswers202WithTheNewAttempt() throws Exception {
        mvc().perform(retryRequest("key-1", "{\"reason\":\"来源已恢复\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.executionId").value(2002))
                .andExpect(jsonPath("$.data.triggerType").value("RETRY"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.attemptNo").value(2))
                .andExpect(jsonPath("$.data.batchId").value("batch-1"));

        ArgumentCaptor<RetryJobCommand> captor = ArgumentCaptor.forClass(RetryJobCommand.class);
        verify(jobs).retry(org.mockito.ArgumentMatchers.eq(1001L), captor.capture(),
                org.mockito.ArgumentMatchers.eq(OPERATOR_ID), any());
        assertThat(captor.getValue().reason()).isEqualTo("来源已恢复");
    }

    /** 重试可以不带请求体（原因不是必填）。 */
    @Test
    void retryAcceptsAnEmptyBody() throws Exception {
        mvc().perform(post("/api/v1/admin/job-executions/{id}/retries", 1001L)
                        .header("Idempotency-Key", "key-1")
                        .principal(authentication()))
                .andExpect(status().isAccepted());

        ArgumentCaptor<RetryJobCommand> captor = ArgumentCaptor.forClass(RetryJobCommand.class);
        verify(jobs).retry(anyLong(), captor.capture(), anyLong(), any());
        assertThat(captor.getValue().reason()).isNull();
    }

    @Test
    void requiresIdempotencyKeyOnRetry() throws Exception {
        mvc().perform(post("/api/v1/admin/job-executions/{id}/retries", 1001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"来源已恢复\"}")
                        .principal(authentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(jobs, times(0)).retry(anyLong(), any(), anyLong(), any());
    }

    @Test
    void replaysTheRetryWithoutRunningItTwice() throws Exception {
        mvc().perform(retryRequest("key-1", "{\"reason\":\"来源已恢复\"}"))
                .andExpect(status().isAccepted());
        mvc().perform(retryRequest("key-1", "{\"reason\":\"来源已恢复\"}"))
                .andExpect(status().isAccepted());

        verify(jobs, times(1)).retry(anyLong(), any(), anyLong(), any());
    }

    /** 同 ADM-JOB-02：执行记录 ID 在路径上，所以也必须是冲突而不是回放。 */
    @Test
    void treatsTheSameKeyOnADifferentExecutionAsAConflict() throws Exception {
        String body = "{\"reason\":\"来源已恢复\"}";
        mvc().perform(retryRequestFor(1001L, "shared-key", body)).andExpect(status().isAccepted());

        mvc().perform(retryRequestFor(2002L, "shared-key", body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        verify(jobs, times(1)).retry(anyLong(), any(), anyLong(), any());
    }

    /**
     * 触发与重试的幂等范围互不干扰。
     *
     * <p>两处若共用一个 scope，同一个键先触发一次再重试一次时，
     * 后者会被回放成前者那条 {@code MANUAL} 记录——调用方拿到 202，
     * 以为重试发生了，而 {@code job_executions} 里根本没有新的尝试。
     */
    @Test
    void keepsTriggerAndRetryInSeparateIdempotencyScopes() throws Exception {
        mvc().perform(triggerRequest("shared-key", TRIGGER_BODY))
                .andExpect(status().isAccepted());
        mvc().perform(retryRequest("shared-key", "{\"reason\":\"来源已恢复\"}"))
                .andExpect(status().isAccepted());

        verify(jobs, times(1)).trigger(any(), any(), anyLong(), any());
        verify(jobs, times(1)).retry(anyLong(), any(), anyLong(), any());
    }

    @Test
    void mapsANonRetryableExecutionTo409() throws Exception {
        when(jobs.retry(anyLong(), any(), anyLong(), any()))
                .thenThrow(AdminException.jobExecutionNotRetryable(1001L, "SUCCESS"));

        mvc().perform(retryRequest("key-1", "{\"reason\":\"来源已恢复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_JOB_EXECUTION_NOT_RETRYABLE"))
                .andExpect(jsonPath("$.message").value(containsString("SUCCESS")));

        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo("ADMIN_JOB_RETRY");
            assertThat(event.resultStatus()).isEqualTo(AuditEvent.FAILURE);
        });
    }

    @Test
    void mapsAnUnknownExecutionTo404OnRetry() throws Exception {
        when(jobs.retry(anyLong(), any(), anyLong(), any()))
                .thenThrow(AdminException.jobExecutionNotFound(9999L));

        mvc().perform(retryRequest("key-1", "{\"reason\":\"来源已恢复\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADMIN_JOB_EXECUTION_NOT_FOUND"));
    }

    // ---------- 装配 ----------

    private MockHttpServletRequestBuilder triggerRequest(String key, String body) {
        return triggerRequestFor(JobNames.MARKET_OVERVIEW_COLLECT, key, body);
    }

    private MockHttpServletRequestBuilder triggerRequestFor(String jobName, String key, String body) {
        MockHttpServletRequestBuilder builder =
                post("/api/v1/admin/job-definitions/{jobName}/executions", jobName)
                        .header("Idempotency-Key", key)
                        .principal(authentication());
        return body == null
                ? builder
                : builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder retryRequest(String key, String body) {
        return retryRequestFor(1001L, key, body);
    }

    private MockHttpServletRequestBuilder retryRequestFor(long executionId, String key, String body) {
        MockHttpServletRequestBuilder builder =
                post("/api/v1/admin/job-executions/{id}/retries", executionId)
                        .header("Idempotency-Key", key)
                        .principal(authentication());
        return body == null
                ? builder
                : builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AdminJobController(
                        jobs,
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
                OPERATOR_ID, "admin", Set.of("ops:job:trigger"), "jti-1",
                Instant.parse("2030-01-01T00:00:00Z"), 0);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static JobExecution running(long executionId, int attemptNo) {
        return failure(null, executionId, JobExecutionStatus.RUNNING, attemptNo, null);
    }

    private static JobExecution retried(long executionId) {
        return new JobExecution(
                executionId, JobNames.MARKET_OVERVIEW_COLLECT, JobNames.MARKET_OVERVIEW_HANDLER,
                "batch-1", 7L, JobTriggerType.RETRY, JobExecutionStatus.RUNNING,
                0, 1, 2, null,
                OffsetDateTime.parse("2026-09-23T10:05:00+08:00"), null, null, null,
                null, null, null, null, "trace-2",
                OffsetDateTime.parse("2026-09-23T10:05:00+08:00"),
                OffsetDateTime.parse("2026-09-23T10:05:00+08:00"));
    }

    private static JobExecution failed(long executionId) {
        return failure(
                new JobExecutionCounts(10, 7, 1, 2, 7),
                executionId, JobExecutionStatus.FAILED, 1, "PROVIDER_TIMEOUT");
    }

    private static JobExecution failure(
            JobExecutionCounts counts,
            long executionId,
            JobExecutionStatus status,
            int attemptNo,
            String errorCategory) {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-09-23T10:00:00+08:00");
        boolean running = status == JobExecutionStatus.RUNNING;
        return new JobExecution(
                executionId, JobNames.MARKET_OVERVIEW_COLLECT, JobNames.MARKET_OVERVIEW_HANDLER,
                "batch-1", running ? null : 7L, JobTriggerType.MANUAL, status,
                0, 1, attemptNo, null,
                startedAt, running ? null : startedAt.plusSeconds(3), null, null,
                counts,
                errorCategory,
                running ? null : "NEWS_SOURCE_TIMEOUT",
                running ? null : "来源超时，已跳过本轮",
                running ? "trace-1" : "trace-2",
                startedAt, running ? startedAt : startedAt.plusSeconds(3));
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
