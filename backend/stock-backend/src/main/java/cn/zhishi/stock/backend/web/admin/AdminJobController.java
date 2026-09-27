package cn.zhishi.stock.backend.web.admin;

import cn.zhishi.stock.admin.application.JobAdminService;
import cn.zhishi.stock.admin.application.RetryJobCommand;
import cn.zhishi.stock.admin.application.TriggerJobCommand;
import cn.zhishi.stock.admin.domain.JobDefinition;
import cn.zhishi.stock.backend.web.AuditRecorder;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.job.JobExecution;
import cn.zhishi.stock.system.job.JobExecutionQuery;
import cn.zhishi.stock.system.job.JobExecutionStatus;
import cn.zhishi.stock.system.job.JobTriggerType;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台定时任务（契约 §16.3 ADM-JOB-01~05）。
 *
 * <h2>只有两个写端点，但它们的影响面最大</h2>
 * 触发与重试都会让平台**立刻动一次外部数据**（拉行情、写资讯、删导出文件）。
 * 因此它们除了权限码之外还有三道约束：白名单（只认得出名字的任务）、
 * 幂等键（缺失即 400，重复点击不会跑两次）、审计（谁在什么时候触发了什么、为什么）。
 * 只读的三个端点不分片、不写审计——与角色列表、操作日志同一条理由。
 *
 * <h2>202 是真的异步</h2>
 * 响应返回时任务尚未执行（由 {@code InProcessJobDispatcher} 在后台线程上跑）。
 * 响应体里带的是那条 {@code RUNNING} 记录的摘要，调用方据此轮询 ADM-JOB-04。
 * 同步跑完再返回 202 会让状态码说谎——见 {@code JobExecutionDispatcher} 的说明。
 *
 * <h2>为什么这一组路径写在同一个类里</h2>
 * {@code /job-definitions} 与 {@code /job-executions} 是两个资源，契约也分了两节，
 * 但它们共用同一批权限码（{@code ops:job:list} 覆盖定义列表与执行分页）与同一套
 * 白名单知识。拆成两个类之后，新人要先找两个文件才能回答"这个任务都有哪些端点"。
 * 类级的 {@code @RequestMapping} 因此只到 {@code /api/v1/admin}。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminJobController {

    /** 幂等范围（契约 §3.7）：同一管理员在同一范围内键唯一。 */
    private static final String TRIGGER_SCOPE = "admin-job:trigger";
    private static final String RETRY_SCOPE = "admin-job:retry";

    private final JobAdminService jobs;
    private final IdempotencyGuard idempotency;
    private final AuditRecorder audit;
    private final Clock clock;

    public AdminJobController(
            JobAdminService jobs, IdempotencyGuard idempotency, AuditRecorder audit, Clock clock) {
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /** ADM-JOB-01：白名单任务定义。 */
    @GetMapping("/job-definitions")
    @PreAuthorize("hasAuthority('ops:job:list')")
    public ApiResponse<List<JobDefinition>> definitions(HttpServletRequest request) {
        return success(jobs.definitions(), request);
    }

    /**
     * ADM-JOB-02：人工触发，202。
     *
     * <p>审计摘要记的是**请求里写的** {@code scopeKey}，不是用例层归一化之后的值
     * （{@code cn} 会被归一成 {@code CN}）。这是 {@code AuditRecorder} 的定位决定的：
     * 审计回答"谁请求了什么"，而归一化结果落在 {@code job_execution_summary} 里。
     * 两处都记同一份归一化后的值，就没法回答"运维当初填的是不是大小写写错了"。
     */
    @PostMapping("/job-definitions/{jobName}/executions")
    @PreAuthorize("hasAuthority('ops:job:trigger')")
    public ResponseEntity<ApiResponse<JobExecution>> trigger(
            Authentication authentication,
            @PathVariable String jobName,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TriggerJobRequest body,
            HttpServletRequest request) {
        long operatorId = principal(authentication).userId();
        // 幂等指纹必须带上路径里的任务名：只拿请求体比对时，"两个不同的任务
        // 共用一个键、请求体都是空"会被判成同一次请求，第二个返回 202 却被回放成
        // 第一个的结果——库里没有任何第二份记录，而调用方以为它跑了。
        TriggerFingerprint fingerprint = TriggerFingerprint.of(jobName, body);
        TriggerJobCommand command = fingerprint.toCommand();

        JobExecution execution = audited(
                authentication,
                request,
                AdminAudit.JOB_TRIGGER,
                AdminAudit.summary(
                        "jobName=" + jobName,
                        "scopeKey=" + (command.scopeKey() == null ? "-" : command.scopeKey()),
                        AdminAudit.pair("reason", command.reason())),
                () -> idempotency.execute(
                        TRIGGER_SCOPE,
                        operatorId,
                        IdempotencyGuard.requireKey(idempotencyKey),
                        fingerprint,
                        JobExecution.class,
                        () -> jobs.trigger(
                                jobName, command, operatorId, TraceIdFilter.current(request))));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(execution, request));
    }

    /** ADM-JOB-03：执行分页，按开始时间倒序。 */
    @GetMapping("/job-executions")
    @PreAuthorize("hasAuthority('ops:job:list')")
    public ApiResponse<PageData<JobExecution>> executions(
            @RequestParam(value = "jobName", required = false) String jobName,
            @RequestParam(value = "providerId", required = false) Long providerId,
            @RequestParam(value = "status", required = false) JobExecutionStatus status,
            @RequestParam(value = "triggerType", required = false) JobTriggerType triggerType,
            @RequestParam(value = "batchId", required = false) String batchId,
            @RequestParam(value = "startedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startedAt,
            @RequestParam(value = "endedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endedAt,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        PageData<JobExecution> data = jobs.list(new JobExecutionQuery(
                jobName, providerId, status, triggerType, batchId, startedAt, endedAt, page, size));
        return success(data, request);
    }

    /** ADM-JOB-04：执行详情。 */
    @GetMapping("/job-executions/{executionId}")
    @PreAuthorize("hasAuthority('ops:job:detail')")
    public ApiResponse<JobExecution> execution(
            @PathVariable long executionId, HttpServletRequest request) {
        return success(jobs.detail(executionId), request);
    }

    /** ADM-JOB-05：重试失败或部分失败的执行，202。 */
    @PostMapping("/job-executions/{executionId}/retries")
    @PreAuthorize("hasAuthority('ops:job:retry')")
    public ResponseEntity<ApiResponse<JobExecution>> retry(
            Authentication authentication,
            @PathVariable long executionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RetryJobRequest body,
            HttpServletRequest request) {
        long operatorId = principal(authentication).userId();
        String reason = body == null ? null : body.reason();
        RetryJobCommand command = new RetryJobCommand(reason);

        JobExecution execution = audited(
                authentication,
                request,
                AdminAudit.JOB_RETRY,
                AdminAudit.summary(
                        "executionId=" + executionId,
                        AdminAudit.pair("reason", command.reason())),
                () -> idempotency.execute(
                        RETRY_SCOPE,
                        operatorId,
                        IdempotencyGuard.requireKey(idempotencyKey),
                        // 同 ADM-JOB-02：执行记录 ID 在路径上，必须进指纹。
                        new RetryFingerprint(Long.toString(executionId), reason),
                        JobExecution.class,
                        () -> jobs.retry(
                                executionId, command, operatorId, TraceIdFilter.current(request))));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(execution, request));
    }

    // ---------- 辅助 ----------

    private <T> T audited(
            Authentication authentication,
            HttpServletRequest request,
            String operation,
            String paramsSummary,
            java.util.function.Supplier<T> action) {
        return audit.audited(
                authentication,
                request,
                operation,
                paramsSummary,
                AdminAudit::failureStatus,
                action);
    }

    private static AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    // ---------- 请求体 ----------

    /** ADM-JOB-02 的请求体。字段全部可选，语义见 {@code TriggerJobCommand}。 */
    public record TriggerJobRequest(
            String scopeKey, Long providerId, Integer shardTotal, String reason) {
    }

    /** ADM-JOB-05 的请求体。 */
    public record RetryJobRequest(String reason) {
    }

    /**
     * ADM-JOB-02 的幂等指纹：请求体 **加上路径上的任务名**。
     *
     * <p>与 {@code AiTaskController.CancelRequest} 同一种处置。少了 {@code jobName}，
     * 同一个键打到两个不同任务上而请求体都为空时，{@code IdempotencyGuard}
     * 会认为"请求体相同"从而回放第一份结果——返回 202、库里没有任何新记录。
     * 那是最难发现的一类故障：状态码是成功的，审计也记了一次成功，
     * 只有执行列表里少了那一行。
     */
    private record TriggerFingerprint(
            String jobName, String scopeKey, Long providerId, Integer shardTotal, String reason) {

        static TriggerFingerprint of(String jobName, TriggerJobRequest body) {
            return body == null
                    ? new TriggerFingerprint(jobName, null, null, null, null)
                    : new TriggerFingerprint(
                            jobName, body.scopeKey(), body.providerId(), body.shardTotal(),
                            body.reason());
        }

        TriggerJobCommand toCommand() {
            return new TriggerJobCommand(scopeKey, providerId, shardTotal, reason);
        }
    }

    /** ADM-JOB-05 的幂等指纹：原因 + **路径上的执行记录 ID**（理由同上）。 */
    private record RetryFingerprint(String executionId, String reason) {
    }
}
