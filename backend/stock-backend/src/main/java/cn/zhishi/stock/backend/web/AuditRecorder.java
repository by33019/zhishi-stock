package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.audit.AuditEvent;
import cn.zhishi.stock.common.audit.AuditLog;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

/**
 * 把 Web 层的事实（发起人、URI、方法、IP、traceId）补进审计事件。
 *
 * <h2>为什么审计在 Web 层记，而不是在用例层</h2>
 * 契约 §22.3 要求审计能回答"谁、从哪、什么时候、请求了什么"。
 * URI / HTTP 方法 / 客户端 IP / traceId 只有 Web 层知道；用例层知道的是
 * "业务上发生了什么"。把两边的信息拼在一起的地方就是这里，而不是让用例层
 * 去认识 {@code HttpServletRequest}（那会让业务域依赖 Servlet API，
 * 也会让它的单测必须起一个容器）。
 *
 * <h2>成功 / 被拒 / 失败分别记什么</h2>
 * <ul>
 *   <li>{@code SUCCESS}：操作完成；</li>
 *   <li>{@code DENIED}：服务按规则**主动拒绝**（如导出限流 {@code EXPORT_RATE_LIMITED}、
 *       后台的"最后一个超级管理员"保护）。它**不是失败**——服务按规则正常工作，
 *       只是这次请求不被允许，运维看的是"谁在刷、谁越权"；</li>
 *   <li>{@code FAILURE}：其余业务失败（对象不存在、状态不满足、未知异常）。</li>
 * </ul>
 * "哪些异常算被拒"是**各域自己的知识**（导出认 429、后台认策略拒绝），
 * 因此由调用方以 {@code failureStatus} 传入，本类只用默认口径兜底。
 * 写成"包住"而不是"在每个出口各调一次"：一个出口漏掉，审计上就会留下一个
 * 说不通的空洞（有成功的记录、没有失败的记录），而那种缺失不会有人察觉。
 *
 * <h2>客户端 IP 取自 {@code X-Forwarded-For} 的第一跳</h2>
 * 部署里 API 前面有反向代理，{@code getRemoteAddr()} 拿到的是代理的地址，
 * 审计价值几乎为零。第一跳是**最接近真实来源**的那个值。
 * 代价是：这个头可以被客户端伪造，因此审计里的 IP 只在"代理会覆写它"的部署下可信；
 * 这一点由部署方保证（nginx 配置 {@code proxy_set_header X-Forwarded-For}）。
 */
public class AuditRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditRecorder.class);

    private static final String CLIENT_IP_HEADER = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";

    /** 不带域内规则的默认口径：异常一律记为 失败。 */
    private static final Function<RuntimeException, String> ALWAYS_FAILURE =
            exception -> AuditEvent.FAILURE;

    private final AuditLog auditLog;

    public AuditRecorder(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** 包住一次操作并记账：成功记 {@code SUCCESS}，抛异常记 {@code FAILURE}，异常原样抛出。 */
    public <T> T audited(
            Authentication authentication,
            HttpServletRequest request,
            String operation,
            String paramsSummary,
            Supplier<T> action) {
        return audited(authentication, request, operation, paramsSummary, ALWAYS_FAILURE, action);
    }

    /**
     * 包住一次操作并记账，失败状态由 {@code failureStatus} 判定。
     *
     * <p>只在"被规则拒绝"与"操作失败"需要分开记的域才传这个函数。
     */
    public <T> T audited(
            Authentication authentication,
            HttpServletRequest request,
            String operation,
            String paramsSummary,
            Function<RuntimeException, String> failureStatus,
            Supplier<T> action) {
        try {
            T result = action.get();
            record(authentication, request, operation, AuditEvent.SUCCESS, paramsSummary);
            return result;
        } catch (RuntimeException exception) {
            String status = failureStatus.apply(exception);
            if (status == null) {
                // 判定函数返回 null 时不静默写成 NULL：sys_log.result_status 是
                // "这次操作结果如何"的唯一答案，写进去一个空值等于丢掉了这个事实。
                LOGGER.warn("审计失败状态判定为 null，回退为 FAILURE：operation={}", operation);
                status = AuditEvent.FAILURE;
            }
            record(authentication, request, operation, status, paramsSummary);
            throw exception;
        }
    }

    private void record(
            Authentication authentication,
            HttpServletRequest request,
            String operation,
            String resultStatus,
            String paramsSummary) {
        AccessTokenPrincipal principal = principalOf(authentication);
        try {
            auditLog.record(new AuditEvent(
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
            // 它出问题绝不该改变"这次操作成功还是失败"的结论。
            LOGGER.error("审计记录失败：operation={}", operation, exception);
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
