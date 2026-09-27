package cn.zhishi.stock.backend.web.admin;

import cn.zhishi.stock.admin.application.AdminAiService;
import cn.zhishi.stock.admin.domain.AdminAiFeedbackStats;
import cn.zhishi.stock.admin.domain.AdminAiOverview;
import cn.zhishi.stock.admin.domain.AdminAiTaskDetail;
import cn.zhishi.stock.admin.domain.AdminAiTaskQuery;
import cn.zhishi.stock.admin.domain.AdminAiTaskSummary;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore.GroupBy;
import cn.zhishi.stock.admin.domain.AdminAiUsageGroup;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
import cn.zhishi.stock.backend.web.AuditRecorder;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台 AI 运营（契约 §19 ADM-AI-01~06）。
 *
 * <h2>读为主，唯一的写是取消</h2>
 * 五个读端点不分片、不写审计（与角色列表、操作日志同一条理由）；
 * 取消是运营动作，带幂等键并入审计。取消的幂等指纹含路径上的 {@code taskId}——
 * 同一键打到两个任务上时不能互相回放（与 ADM-JOB-02 同一条教训）。
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {

    private static final String CANCEL_SCOPE = "admin-ai-task:cancel";

    private final AdminAiService ai;
    private final IdempotencyGuard idempotency;
    private final AuditRecorder audit;
    private final Clock clock;

    public AdminAiController(
            AdminAiService ai, IdempotencyGuard idempotency, AuditRecorder audit, Clock clock) {
        this.ai = ai;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /** ADM-AI-01：运营总览。 */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('ai:ops:overview')")
    public ApiResponse<AdminAiOverview> overview(
            @RequestParam(value = "startAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startAt,
            @RequestParam(value = "endAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endAt,
            HttpServletRequest request) {
        return success(ai.overview(Optional.ofNullable(startAt), Optional.ofNullable(endAt)), request);
    }

    /** ADM-AI-02：任务元数据分页。 */
    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('ai:ops:task-list')")
    public ApiResponse<PageData<AdminAiTaskSummary>> tasks(
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestParam(value = "status", required = false)
                    cn.zhishi.stock.ai.domain.AiTaskStatus status,
            @RequestParam(value = "providerCode", required = false) String providerCode,
            @RequestParam(value = "errorCategory", required = false)
                    cn.zhishi.stock.ai.domain.LlmErrorCategory errorCategory,
            @RequestParam(value = "startedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startedAt,
            @RequestParam(value = "endedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endedAt,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        PageData<AdminAiTaskSummary> data = ai.listTasks(new AdminAiTaskQuery(
                taskId, userId, scene, status, providerCode, errorCategory,
                startedAt, endedAt, page, size));
        return success(data, request);
    }

    /** ADM-AI-03：任务详情（只有元数据，读正文需单独合规授权，MVP 不提供）。 */
    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('ai:ops:task-detail')")
    public ApiResponse<AdminAiTaskDetail> task(
            @PathVariable long taskId, HttpServletRequest request) {
        return success(ai.taskDetail(taskId), request);
    }

    /** ADM-AI-04：取消卡死或风险任务。 */
    @PostMapping("/tasks/{taskId}/cancel")
    @PreAuthorize("hasAuthority('ai:ops:task-cancel')")
    public ApiResponse<AdminAiTaskDetail> cancel(
            org.springframework.security.core.Authentication authentication,
            @PathVariable long taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CancelTaskRequest body,
            HttpServletRequest request) {
        String reason = body == null ? null : body.reason();

        AdminAiTaskDetail canceled = audited(
                authentication,
                request,
                AdminAudit.AI_TASK_CANCEL,
                AdminAudit.summary(
                        "taskId=" + taskId,
                        AdminAudit.pair("reason", reason)),
                () -> idempotency.execute(
                        CANCEL_SCOPE,
                        principal(authentication).userId(),
                        IdempotencyGuard.requireKey(idempotencyKey),
                        new CancelFingerprint(taskId, reason),
                        AdminAiTaskDetail.class,
                        () -> ai.cancelTask(taskId)));
        return success(canceled, request);
    }

    /** ADM-AI-05：分组用量。 */
    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('ai:ops:usage')")
    public ApiResponse<List<AdminAiUsageGroup>> usage(
            @RequestParam(value = "groupBy", defaultValue = "DAY") GroupBy groupBy,
            @RequestParam(value = "startAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startAt,
            @RequestParam(value = "endAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endAt,
            @RequestParam(value = "providerCode", required = false) String providerCode,
            @RequestParam(value = "modelCode", required = false) String modelCode,
            @RequestParam(value = "resultStatus", required = false)
                    cn.zhishi.stock.ai.domain.AiUsageResultStatus resultStatus,
            HttpServletRequest request) {
        return success(ai.usage(
                        groupBy,
                        Optional.ofNullable(startAt), Optional.ofNullable(endAt),
                        Optional.ofNullable(providerCode), Optional.ofNullable(modelCode),
                        Optional.ofNullable(resultStatus).map(Enum::name)),
                request);
    }

    /** ADM-AI-06：反馈统计。 */
    @GetMapping("/feedback-statistics")
    @PreAuthorize("hasAuthority('ai:ops:feedback')")
    public ApiResponse<AdminAiFeedbackStats> feedbackStatistics(
            @RequestParam(value = "startAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startAt,
            @RequestParam(value = "endAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endAt,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestParam(value = "feedbackType", required = false) AiFeedbackType feedbackType,
            @RequestParam(value = "reasonCode", required = false) String reasonCode,
            HttpServletRequest request) {
        return success(ai.feedbackStatistics(
                        Optional.ofNullable(startAt), Optional.ofNullable(endAt),
                        Optional.ofNullable(scene), Optional.ofNullable(feedbackType),
                        Optional.ofNullable(reasonCode)),
                request);
    }

    // ---------- 辅助 ----------

    private <T> T audited(
            org.springframework.security.core.Authentication authentication,
            HttpServletRequest request,
            String operation,
            String paramsSummary,
            java.util.function.Supplier<T> action) {
        return audit.audited(
                authentication, request, operation, paramsSummary,
                AdminAudit::failureStatus, action);
    }

    private static AccessTokenPrincipal principal(
            org.springframework.security.core.Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    // ---------- 请求体 ----------

    /** ADM-AI-04 的请求体。 */
    public record CancelTaskRequest(String reason) {
    }

    /** ADM-AI-04 的幂等指纹：原因 + 路径上的任务 ID。 */
    private record CancelFingerprint(long taskId, String reason) {
    }
}
