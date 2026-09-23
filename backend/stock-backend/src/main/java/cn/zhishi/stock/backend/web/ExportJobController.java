package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.export.application.ExportDownload;
import cn.zhishi.stock.export.application.ExportJobAccepted;
import cn.zhishi.stock.export.application.ExportJobService;
import cn.zhishi.stock.export.application.ExportJobView;
import cn.zhishi.stock.export.domain.ExportRequest;
import cn.zhishi.stock.export.domain.RankingExportFilters;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导出接口（契约 §9.2 EXP-01~EXP-04）。
 *
 * <h2>四个端点全部要求登录，且全部只认本人</h2>
 * 路径在 {@code /api/v1/export-jobs} 下，不在 {@code SecurityConfiguration} 的任何
 * {@code permitAll} 白名单里。归属校验在**用例层**：别人的作业与不存在的作业返回
 * 同一句话，所以这里不会因为多写一次判断而泄露"这个 id 存在"（契约 §23.1）。
 *
 * <h2>创建返回 202，不是 200</h2>
 * 文件此刻并不存在：作业只是**已受理**，生成在另一个线程上跑。
 * 用 200 会让调用方以为"创建完就能下载"，而它拿到的只是一个排队中的作业。
 *
 * <h2>只有创建需要 {@code Idempotency-Key}</h2>
 * 契约 §9.2 在 EXP-01 一行里写明。它挡的是"用户连点两次导出按钮"——
 * 重放返回的是**同一次**受理结果，因此也只消耗一次限流次数
 * （限流计数在幂等回放之后，见 {@code ExportJobService#create}）。
 */
@RestController
@RequestMapping("/api/v1/export-jobs")
public class ExportJobController {

    /** 幂等范围（契约 §3.7）：同一用户在同一范围内键唯一。 */
    private static final String CREATE_SCOPE = "export-job:create";

    private static final String OPERATION_CREATE = "EXPORT_CREATE";
    private static final String OPERATION_DOWNLOAD = "EXPORT_DOWNLOAD";
    private static final String OPERATION_DELETE = "EXPORT_DELETE";

    /** xlsx 的 MIME 类型。契约只要求"二进制文件"，这里给出准确类型，浏览器才不会当成待保存的乱码。 */
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** 契约 §9.2 EXP-03 要求的响应头：数据截止时间。 */
    private static final String HEADER_DATA_CUTOFF = "X-Data-Cutoff-At";

    private final ExportJobService jobs;
    private final IdempotencyGuard idempotency;
    private final ExportAuditRecorder audit;
    private final Clock clock;

    public ExportJobController(
            ExportJobService jobs,
            IdempotencyGuard idempotency,
            ExportAuditRecorder audit,
            Clock clock) {
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /** EXP-01：创建导出任务（HTTP 202）。 */
    @PostMapping
    public ResponseEntity<ApiResponse<ExportJobAccepted>> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ExportJobRequest body,
            HttpServletRequest request) {
        AccessTokenPrincipal principal = principal(authentication);
        String key = IdempotencyGuard.requireKey(idempotencyKey);
        // 解析失败（exportType 缺失/非法）会在这里直接 400，不进审计——
        // 它不是一次"导出操作"，而是一次写错了的请求。
        ExportRequest domain = body.toDomain();

        ExportJobAccepted accepted = audit.audited(
                authentication,
                request,
                OPERATION_CREATE,
                summarize(domain),
                () -> idempotency.execute(
                        CREATE_SCOPE,
                        principal.userId(),
                        key,
                        body,
                        ExportJobAccepted.class,
                        () -> jobs.create(principal.userId(), domain)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(success(accepted, request));
    }

    /** EXP-02：查询本人导出任务状态。 */
    @GetMapping("/{exportId}")
    public ApiResponse<ExportJobView> status(
            Authentication authentication,
            @PathVariable String exportId,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(jobs.status(userId, exportId), request);
    }

    /**
     * EXP-03：下载已完成文件。
     *
     * <p>响应头里的 {@code X-Data-Cutoff-At} 与文件说明区里的"数据截止时间"同源
     * （都来自 {@link ExportDownload#dataCutoffAt()}）：两处各取一次，
     * 就会出现"响应头说 15:00、文件说 14:30"这种只在跨批次时才暴露的分叉。
     */
    @GetMapping("/{exportId}/download")
    public ResponseEntity<byte[]> download(
            Authentication authentication,
            @PathVariable String exportId,
            HttpServletRequest request) {
        AccessTokenPrincipal principal = principal(authentication);
        ExportDownload download = audit.audited(
                authentication,
                request,
                OPERATION_DOWNLOAD,
                summarize(exportId),
                () -> jobs.download(principal.userId(), exportId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        // filename 与 filename* 都给出：前者兼容老客户端，后者保证中文/空格类文件名不乱码。
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build());
        if (download.dataCutoffAt() != null) {
            headers.add(HEADER_DATA_CUTOFF, download.dataCutoffAt().toString());
        }
        return ResponseEntity.ok().headers(headers).body(download.content());
    }

    /** EXP-04：删除本人临时导出文件与作业记录。 */
    @DeleteMapping("/{exportId}")
    public ApiResponse<DeletedResult> delete(
            Authentication authentication,
            @PathVariable String exportId,
            HttpServletRequest request) {
        AccessTokenPrincipal principal = principal(authentication);
        boolean deleted = audit.audited(
                authentication,
                request,
                OPERATION_DELETE,
                summarize(exportId),
                () -> jobs.delete(principal.userId(), exportId));
        return success(new DeletedResult(deleted), request);
    }

    /**
     * 审计用的参数摘要。
     *
     * <p>只放**白名单字段**，不放原始请求体：契约 §22.2 要求"日志按字段白名单记录，
     * 请求参数先脱敏"。导出请求里没有密钥，但筛选条件属于用户行为，
     * 逐项列出比整段 body 更可读，也不会把将来新增的敏感字段顺手带进日志。
     */
    private static String summarize(ExportRequest request) {
        RankingExportFilters filters = request.filters();
        List<String> parts = new ArrayList<>();
        parts.add("exportType=" + request.exportType().code());
        parts.add("rankingType=" + filters.rankingType());
        if (present(filters.exchangeCodes())) {
            parts.add("exchangeCodes=" + filters.exchangeCodes().trim());
        }
        if (present(filters.boardCodes())) {
            parts.add("boardCodes=" + filters.boardCodes().trim());
        }
        if (present(filters.sectorId())) {
            parts.add("sectorId=" + filters.sectorId().trim());
        }
        parts.add("excludeSt=" + filters.excludeSt());
        parts.add("excludeSuspended=" + filters.excludeSuspended());
        parts.add("columns=" + request.columns().size());
        return String.join(";", parts);
    }

    private static String summarize(String exportId) {
        return "exportId=" + exportId;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    /** EXP-04 的响应体，字段名与契约一致。 */
    public record DeletedResult(boolean deleted) {
    }
}
