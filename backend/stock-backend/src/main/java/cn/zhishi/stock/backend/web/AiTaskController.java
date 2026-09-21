package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiCancelResult;
import cn.zhishi.stock.ai.application.AiFollowUpRequest;
import cn.zhishi.stock.ai.application.AiTaskAccepted;
import cn.zhishi.stock.ai.application.AiTaskCreationRequest;
import cn.zhishi.stock.ai.application.AiTaskService;
import cn.zhishi.stock.ai.application.AiTaskSummary;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 任务接口（契约 §13.2 AI-03 / AI-04 / AI-06 / AI-07 / AI-08）。
 *
 * <h2>全部要求登录，且全部只认本人</h2>
 * 路径在 {@code /api/v1/ai} 下，不在 {@code SecurityConfiguration} 的任何
 * {@code permitAll} 白名单里。归属校验在**用例层**（{@code AiTaskService}）：
 * 别人的任务与不存在的任务返回同一句 {@code AI_TASK_NOT_FOUND}，
 * 所以这里不会因为多写一次判断而泄露"这个 ID 存在"（契约 §23.1）。
 *
 * <h2>四个写接口都要 {@code Idempotency-Key}</h2>
 * 契约 §13.2 逐个写明。重放的是**用例层的返回值**（{@code AiTaskAccepted} 等），
 * 响应壳每次重新构造——{@code traceId} 与 {@code timestamp} 描述的是"这一次响应"，
 * 复用上一次的会让排查时对不上日志。
 *
 * <h2>创建类接口返回 202 而不是 200</h2>
 * 任务此刻只是**已受理**：真正生成报告的是另一个进程（{@code stock-ai-worker}）。
 * 用 200 会让调用方以为"分析已完成"，而它拿到的只是一个排队中的任务。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiTaskController {

    /** 幂等范围（契约 §3.7）：同一用户在同一范围内键唯一。 */
    private static final String CREATE_SCOPE = "ai-task:create";

    private static final String CANCEL_SCOPE = "ai-task:cancel";
    private static final String RETRY_SCOPE = "ai-task:retry";
    private static final String FOLLOW_UP_SCOPE = "ai-task:follow-up";

    private final AiTaskService tasks;
    private final IdempotencyGuard idempotency;
    private final Clock clock;

    public AiTaskController(
            AiTaskService tasks, IdempotencyGuard idempotency, Clock clock) {
        this.tasks = tasks;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** AI-03：创建任务。 */
    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<AiTaskAccepted>> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AiTaskCreationRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        String key = IdempotencyGuard.requireKey(idempotencyKey);
        AiTaskAccepted accepted = idempotency.execute(
                CREATE_SCOPE,
                userId,
                key,
                body,
                AiTaskAccepted.class,
                () -> tasks.create(body, userId, key, TraceIdFilter.current(request)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(accepted, request));
    }

    /** AI-04：查询本人任务。 */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AiTaskSummary> get(
            Authentication authentication,
            @PathVariable long taskId,
            HttpServletRequest request) {
        return success(tasks.get(taskId, principal(authentication).userId()), request);
    }

    /**
     * AI-06：请求取消。
     *
     * <p>刻意**不**返回 202：取消是即时生效的（要么置上意图，要么因为已是终态而无效），
     * 没有"稍后完成"的语义。
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<AiCancelResult> cancel(
            Authentication authentication,
            @PathVariable long taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        String key = IdempotencyGuard.requireKey(idempotencyKey);
        AiCancelResult result = idempotency.execute(
                CANCEL_SCOPE,
                userId,
                key,
                new CancelRequest(Long.toString(taskId)),
                AiCancelResult.class,
                () -> tasks.cancel(taskId, userId));
        return success(result, request);
    }

    /** AI-07：重试失败或超时的任务，创建**新任务**（不覆盖旧任务与旧用量）。 */
    @PostMapping("/tasks/{taskId}/retry")
    public ResponseEntity<ApiResponse<AiTaskAccepted>> retry(
            Authentication authentication,
            @PathVariable long taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RetryRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        String key = IdempotencyGuard.requireKey(idempotencyKey);
        String question = body == null ? null : body.question();
        AiTaskAccepted accepted = idempotency.execute(
                RETRY_SCOPE,
                userId,
                key,
                new RetryRequest(question),
                AiTaskAccepted.class,
                () -> tasks.retry(taskId, userId, key, question, TraceIdFilter.current(request)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(accepted, request));
    }

    /**
     * AI-08：在活动会话里追问。
     *
     * <p>路径按契约原文是 {@code /ai/sessions/{sessionId}/follow-up-tasks}。
     */
    @PostMapping("/sessions/{sessionId}/follow-up-tasks")
    public ResponseEntity<ApiResponse<AiTaskAccepted>> followUp(
            Authentication authentication,
            @PathVariable long sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AiFollowUpRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        String key = IdempotencyGuard.requireKey(idempotencyKey);
        AiTaskAccepted accepted = idempotency.execute(
                FOLLOW_UP_SCOPE,
                userId,
                key,
                body,
                AiTaskAccepted.class,
                () -> tasks.followUp(
                        sessionId, userId, body, key, TraceIdFilter.current(request)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(accepted, request));
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    /**
     * 取消请求的幂等指纹。
     *
     * <p>取消没有请求体，但 {@code IdempotencyGuard} 需要一个可比对的对象——
     * 否则"同一个键"会被当成"不同的请求"。用 taskId 当指纹，于是同一个键打到
     * 两个不同任务上会被判为冲突（{@code IdempotencyKeyConflictException}），
     * 而不是安静地返回第一个任务的取消结果。
     */
    private record CancelRequest(String taskId) {
    }

    /** AI-07 的可选请求体。 */
    public record RetryRequest(String question) {
    }
}
