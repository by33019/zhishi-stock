package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiHistoryService;
import cn.zhishi.stock.ai.application.AiMessageView;
import cn.zhishi.stock.ai.application.AiSessionDetail;
import cn.zhishi.stock.ai.application.AiSessionSummaryView;
import cn.zhishi.stock.ai.application.InvalidAiHistoryQueryException;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 会话历史接口（契约 §13.3 HIS-01 / HIS-05）。
 *
 * <h2>为什么单独一个控制器</h2>
 * 同 {@code AiReportController}：路径都在 {@code /api/v1/ai} 下但资源不同——
 * 任务是过程对象、报告是结果对象、会话是容器、消息是会话内的条目。
 * HIS-03 / HIS-04（改名、收藏、软删）也归这里，它们是**写**路径、
 * 需要 {@code If-Match}，与这两个读接口分开排期。
 *
 * <h2>查询参数一律收成 String</h2>
 * 时间与页码都由用例层解析，而不是靠 Spring 的类型转换：
 * 转换失败会被 Spring 转成 {@code MethodArgumentTypeMismatchException}，
 * 语义上把"时间格式不对"混同成"参数类型错误"，返回的消息对调用方没有指导意义。
 * 这与 STK-01 / NEWS-01 / AI-02 的既有处置一致。
 *
 * <h2>全部要求登录，且只认本人</h2>
 * 不在 {@code SecurityConfiguration} 的任何 {@code permitAll} 白名单里；
 * 归属校验在用例层，他人的会话与不存在的会话返回同一句 {@code AI_SESSION_NOT_FOUND}。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiSessionController {

    private final AiHistoryService history;
    private final Clock clock;

    public AiSessionController(AiHistoryService history, Clock clock) {
        this.history = history;
        this.clock = clock;
    }

    /** HIS-01：查询本人有效分析历史，按最后活动时间倒序。 */
    @GetMapping("/sessions")
    public ApiResponse<PageData<AiSessionSummaryView>> listSessions(
            Authentication authentication,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) String startAt,
            @RequestParam(required = false) String endAt,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(
                history.listSessions(
                        userId,
                        scene,
                        keyword,
                        favorite,
                        parseTime(startAt, "startAt"),
                        parseTime(endAt, "endAt"),
                        page,
                        size),
                request);
    }

    /**
     * HIS-02：会话详情。
     *
     * <p>路径与 HIS-01 的 {@code /sessions} 只差一个路径变量，Spring 按精确匹配优先，
     * 不会与列表接口冲突。
     */
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<AiSessionDetail> getSession(
            Authentication authentication,
            @PathVariable long sessionId,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(history.getSession(sessionId, userId), request);
    }

    /** HIS-05：获取本人会话消息（不含 {@code SYSTEM} 内部 Prompt）。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<PageData<AiMessageView>> messages(
            Authentication authentication,
            @PathVariable long sessionId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(history.messages(sessionId, userId, page, size), request);
    }

    /**
     * 解析可选的时间参数。
     *
     * <p>空串按"未提供"处理而不是报错：查询串里经常出现 {@code ?startAt=}（表单未填时
     * 前端仍会拼上这个键），把它当成非法输入会让一个空表单直接失败。
     */
    private static OffsetDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidAiHistoryQueryException(
                    field + " 必须是 ISO-8601 带偏移的时间，例如 2026-09-22T15:00:00+08:00：" + value);
        }
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }
}
