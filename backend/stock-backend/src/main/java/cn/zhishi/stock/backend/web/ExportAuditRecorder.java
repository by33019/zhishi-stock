package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.export.application.ExportErrorCode;
import cn.zhishi.stock.export.application.ExportException;
import cn.zhishi.stock.export.domain.ExportAuditEvent;
import cn.zhishi.stock.export.domain.ExportAuditLog;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/**
 * 把 Web 层的事实（发起人、URI、方法、IP、traceId）补进导出审计事件。
 *
 * <h2>为什么审计在 Web 层记，而不是在用例层</h2>
 * 契约 §22.3 要求审计能回答"谁、从哪、什么时候、请求了什么"。
 * URI / HTTP 方法 / 客户端 IP / traceId 只有 Web 层知道；用例层知道的是
 * "业务上发生了什么"。把两边的信息拼在一起的地方就是这里，而不是让用例层
 * 去认识 {@code HttpServletRequest}（那会让导出域依赖 Servlet API，
 * 也会让它的单测必须起一个容器）。
 *
 * <h2>成功 / 被拒 / 失败分别记什么</h2>
 * <ul>
 *   <li>{@code SUCCESS}：操作完成；</li>
 *   <li>{@code DENIED}：被限流挡下（{@code EXPORT_RATE_LIMITED}）。它**不是失败**——
 *       服务按规则正常工作，只是这次请求不被允许，运维看的是"谁在刷"；</li>
 *   <li>{@code FAILURE}：其余业务失败（作业不存在、文件没就绪、超过行数上限等）。</li>
 * </ul>
 * 生成阶段的失败由 {@code ExportJobService} 自己记（那时已经没有请求上下文了）。
 *
 * <h2>客户端 IP 取自 {@code X-Forwarded-For} 的第一跳</h2>
 * 部署里 API 前面有反向代理，{@code getRemoteAddr()} 拿到的是代理的地址，
 * 审计价值几乎为零。第一跳是**最接近真实来源**的那个值。
 * 代价是：这个头可以被客户端伪造，因此审计里的 IP 只在"代理会覆写它"的部署下可信；
 * 这一点由部署方保证（nginx 配置 {@code proxy_set_header X-Forwarded-For}）。
 */
public class ExportAuditRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportAuditRecorder.class);

    private static final String CLIENT_IP_HEADER = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";

    private final ExportAuditLog auditLog;

    public ExportAuditRecorder(ExportAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * 包住一次导出操作并记账，异常原样抛出。
     *
     * <p>写成"包住"而不是"在每个出口各调一次"：一个出口漏掉，审计上就会留下一个
     * 说不通的空洞（有成功的记录、没有失败的记录），而那种缺失不会有人察觉。
     */
    public <T> T audited(
            Authentication authentication,
            HttpServletRequest request,
            String operation,
            String paramsSummary,
            Supplier<T> action) {
        try {
            T result = action.get();
            record(authentication, request, operation, ExportAuditEvent.SUCCESS, paramsSummary);
            return result;
        } catch (ExportException exception) {
            record(authentication, request, operation, statusOf(exception), paramsSummary);
            throw exception;
        } catch (RuntimeException exception) {
            record(authentication, request, operation, ExportAuditEvent.FAILURE, paramsSummary);
            throw exception;
        }
    }

    private static String statusOf(ExportException exception) {
        return exception.code() == ExportErrorCode.RATE_LIMITED
                ? ExportAuditEvent.DENIED
                : ExportAuditEvent.FAILURE;
    }

    private void record(
            Authentication authentication,
            HttpServletRequest request,
            String operation,
            String resultStatus,
            String paramsSummary) {
        AccessTokenPrincipal principal = principalOf(authentication);
        try {
            auditLog.record(new ExportAuditEvent(
                    principal == null ? 0L : principal.userId(),
                    principal == null ? null : principal.username(),
                    operation,
                    request.getRequestURI(),
                    request.getMethod(),
                    resultStatus,
                    paramsSummary,
                    clientIp(request),
                    TraceIdFilter.current(request)));
        } catch (RuntimeException exception) {
            // 审计实现本身已经吞掉了写库异常，这里再兜一层：审计是旁路事实，
            // 它出问题绝不该改变"这次导出成功还是失败"的结论。
            LOGGER.error("导出审计记录失败：operation={}", operation, exception);
        }
    }

    private static AccessTokenPrincipal principalOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return authentication.getPrincipal() instanceof AccessTokenPrincipal principal
                ? principal
                : null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(CLIENT_IP_HEADER);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? UNKNOWN : remote;
    }
}
