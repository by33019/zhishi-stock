package cn.zhishi.stock.backend.web.admin;

import cn.zhishi.stock.admin.application.OperationLogService;
import cn.zhishi.stock.admin.domain.OperationLogDetail;
import cn.zhishi.stock.admin.domain.OperationLogEntry;
import cn.zhishi.stock.admin.domain.OperationLogQuery;
import cn.zhishi.stock.admin.domain.OperationResultStatus;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台操作日志（契约 §16.2 LOG-01/02）。
 *
 * <h2>两个端点，两个权限码</h2>
 * {@code sys:log:list} 只给列表，{@code sys:log:detail} 才能看到参数摘要。
 * 这不是把同一个东西切成两半——详情里是可能含敏感信息的自由文本，
 * 而"能看到有哪些操作"与"能看到操作带什么参数"是两种不同强度的权限。
 *
 * <h2>只读接口不写审计</h2>
 * 与 {@code AdminRoleController} 同一条理由：契约 §22.3 的审计范围是写操作。
 * 把查询也记进去，会让 {@code sys_log} 变成"访问记录"，而它一旦被自己的查询灌满，
 * 就没人再能从里面找出真正的操作了。日志的访问记录另有其表（Web 层访问日志）。
 *
 * <h2>时间参数是 ISO-8601 带偏移量</h2>
 * {@code 2026-09-23T00:00:00+08:00}。只用 {@code LocalDate} 会让"今天"
 * 取决于服务器时区，而查询方想说的往往是"某个具体时刻之后"。
 * 缺省与上限（7 天 / 90 天）在 {@code OperationLogService} 里落地，不在这里。
 */
@RestController
@RequestMapping("/api/v1/admin/operation-logs")
public class AdminOperationLogController {

    private final OperationLogService logs;
    private final Clock clock;

    public AdminOperationLogController(OperationLogService logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    /** LOG-01：分页查询操作日志，默认最近 7 天、上限 90 天。 */
    @GetMapping
    @PreAuthorize("hasAuthority('sys:log:list')")
    public ApiResponse<PageData<OperationLogEntry>> list(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "operation", required = false) String operation,
            @RequestParam(value = "resultStatus", required = false)
                    OperationResultStatus resultStatus,
            @RequestParam(value = "httpMethod", required = false) String httpMethod,
            @RequestParam(value = "requestUri", required = false) String requestUri,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "ip", required = false) String ip,
            @RequestParam(value = "startedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startedAt,
            @RequestParam(value = "endedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endedAt,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        OperationLogQuery query = new OperationLogQuery(
                userId,
                username,
                operation,
                resultStatus == null ? null : resultStatus.name(),
                httpMethod,
                requestUri,
                traceId,
                ip,
                startedAt,
                endedAt,
                page,
                size);
        return success(logs.list(query), request);
    }

    /** LOG-02：日志详情，参数摘要已二次脱敏。 */
    @GetMapping("/{logId}")
    @PreAuthorize("hasAuthority('sys:log:detail')")
    public ApiResponse<OperationLogDetail> detail(
            @PathVariable long logId, HttpServletRequest request) {
        return success(logs.detail(logId), request);
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }
}
