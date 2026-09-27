package cn.zhishi.stock.backend.web.ratelimit;

import cn.zhishi.stock.system.ratelimit.RateLimitDecision;
import cn.zhishi.stock.system.ratelimit.RateLimitExceededException;
import cn.zhishi.stock.system.ratelimit.RequestRateLimiter;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 全站请求限流拦截器（契约 §22.1 基线的 Web 层入口）。
 *
 * <h2>规则按"最具体者优先"匹配</h2>
 * 搜索（{@code /securities/search}）落在行情前缀之内，因此规则表是有序的：
 * 第一条命中的规则生效，后面的不再看。新增更具体的前缀时插在前面。
 *
 * <h2>维度：登录用 userId，未登录用 IP</h2>
 * 契约给公共行情的维度是 IP、给自选写的是用户——统一成"登录用 userId、
 * 未登录用 IP"一个口径：自选写本来就必须登录；公共行情匿名居多，
 * 登录用户按 userId 计数比按 IP 更公平（NAT 后面一整个办公室共用一个 IP）。
 *
 * <h2>不在拦截器里的限流</h2>
 * 登录（账号+IP，控制器里拿得到请求体）与后台人工触发（管理员+任务名，
 * 控制器里拿得到路径参数）各自在控制器入口调用同一份 {@link RequestRateLimiter}；
 * AI 任务创建的用户并发/日配额/全局并发由 AI 域自己的闸门负责（契约 §13.5）。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 一条限流规则：路径前缀、允许的 HTTP 方法、桶类别、上限与窗口。 */
    private record Rule(String pathPrefix, String method, String category, int limit, Duration window) {
    }

    private static final List<Rule> RULES = List.of(
            // 证券搜索（§22.1：60 次/分钟，IP 或用户）——必须排在公共行情之前
            new Rule("/api/v1/securities/search", "GET", "search", 60, Duration.ofMinutes(1)),
            // 公共行情查询（§22.1：120 次/分钟）
            new Rule("/api/v1/markets", "GET", "public-market", 120, Duration.ofMinutes(1)),
            new Rule("/api/v1/securities", "GET", "public-market", 120, Duration.ofMinutes(1)),
            new Rule("/api/v1/stock-rankings", "GET", "public-market", 120, Duration.ofMinutes(1)),
            new Rule("/api/v1/sectors", "GET", "public-market", 120, Duration.ofMinutes(1)),
            new Rule("/api/v1/news", "GET", "public-market", 120, Duration.ofMinutes(1)),
            new Rule("/api/v1/quotes", "POST", "public-market", 120, Duration.ofMinutes(1)),
            // 自选写操作（§22.1：60 次/分钟，用户）
            new Rule("/api/v1/watchlist", "POST", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist", "PUT", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist", "PATCH", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist", "DELETE", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-groups", "POST", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-groups", "PUT", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-groups", "PATCH", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-groups", "DELETE", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-items", "POST", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-items", "PUT", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-items", "PATCH", "watchlist-write", 60, Duration.ofMinutes(1)),
            new Rule("/api/v1/watchlist-items", "DELETE", "watchlist-write", 60, Duration.ofMinutes(1)));

    private final RequestRateLimiter limiter;

    public RateLimitInterceptor(RequestRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        Optional<Rule> matched = RULES.stream()
                .filter(rule -> rule.method().equalsIgnoreCase(method))
                .filter(rule -> path.startsWith(rule.pathPrefix()))
                .findFirst();
        if (matched.isEmpty()) {
            return true;
        }
        Rule rule = matched.get();
        RateLimitDecision decision = limiter.acquire(
                rule.category() + ":" + dimensionOf(request), rule.limit(), rule.window());
        response.setHeader("RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    decision.limit(), decision.resetEpochSeconds(), decision.retryAfterSeconds());
        }
        return true;
    }

    /** 登录用 userId，未登录用 IP（X-Forwarded-For 第一跳，由部署方保证）。 */
    private static String dimensionOf(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AccessTokenPrincipal principal) {
            return "u:" + principal.userId();
        }
        return "ip:" + clientIp(request);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
