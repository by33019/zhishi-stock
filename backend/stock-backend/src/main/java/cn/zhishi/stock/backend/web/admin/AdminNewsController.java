package cn.zhishi.stock.backend.web.admin;

import cn.zhishi.stock.admin.application.AdminNewsRelationService;
import cn.zhishi.stock.admin.application.AdminNewsSourceService;
import cn.zhishi.stock.admin.domain.AdminNewsRelationQuery;
import cn.zhishi.stock.admin.domain.AdminNewsRelationView;
import cn.zhishi.stock.admin.domain.AdminNewsSourcePatch;
import cn.zhishi.stock.admin.domain.AdminNewsSourceQuery;
import cn.zhishi.stock.admin.domain.NewAdminNewsSource;
import cn.zhishi.stock.backend.web.AuditRecorder;
import cn.zhishi.stock.backend.web.IfMatch;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台资讯治理（契约 §17.1 ADM-NEWS-01~04 来源管理 + §17.2 ADM-NEWS-05~08 关联审核）。
 *
 * <h2>两个资源写在一个类里</h2>
 * 与 {@code AdminJobController} 同一条理由：来源与关联共用同一批领域词汇
 * （授权状态、目标类型）与同一套对外标识桥接，拆成两个类后新人要先找
 * 两个文件才能回答"后台的资讯治理都有哪些端点"。
 *
 * <h2>写端点的三道约束与任务管理一致</h2>
 * 创建来源、创建关联带幂等键（缺失 400、重复回放第一次结果）；修改来源带
 * If-Match 乐观锁；五个写操作全部入审计。审计的"被拒"口径沿用
 * {@link AdminAudit#failureStatus}：资源不存在是"没做成"（FAILURE），
 * 后台没有超管保护之外的第二类拒绝。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminNewsController {

    /** 幂等范围（契约 §3.7）：同一管理员在同一范围内键唯一。 */
    private static final String CREATE_SOURCE_SCOPE = "admin-news-source:create";
    private static final String CREATE_RELATION_SCOPE = "admin-news-relation:create";

    private final AdminNewsSourceService sources;
    private final AdminNewsRelationService relations;
    private final IdempotencyGuard idempotency;
    private final AuditRecorder audit;
    private final Clock clock;

    public AdminNewsController(
            AdminNewsSourceService sources,
            AdminNewsRelationService relations,
            IdempotencyGuard idempotency,
            AuditRecorder audit,
            Clock clock) {
        this.sources = sources;
        this.relations = relations;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /** ADM-NEWS-01：来源分页。 */
    @GetMapping("/news-sources")
    @PreAuthorize("hasAuthority('news:source:list')")
    public ApiResponse<PageData<NewsSource>> listSources(
            @RequestParam(value = "providerId", required = false) Long providerId,
            @RequestParam(value = "sourceType", required = false) NewsSourceType sourceType,
            @RequestParam(value = "authorizationStatus", required = false)
                    NewsSource.AuthorizationStatus authorizationStatus,
            @RequestParam(value = "status", required = false) NewsSource.SourceStatus status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        PageData<NewsSource> data = sources.list(
                new AdminNewsSourceQuery(providerId, sourceType, authorizationStatus, status, page, size));
        return success(data, request);
    }

    /** ADM-NEWS-02：来源详情。 */
    @GetMapping("/news-sources/{sourceId}")
    @PreAuthorize("hasAuthority('news:source:detail')")
    public ApiResponse<NewsSource> source(
            @PathVariable long sourceId, HttpServletRequest request) {
        return success(sources.detail(sourceId), request);
    }

    /** ADM-NEWS-03：创建来源。授权状态由服务端从授权区间推导，请求体里没有这个字段。 */
    @PostMapping("/news-sources")
    @PreAuthorize("hasAuthority('news:source:create')")
    public ResponseEntity<ApiResponse<NewsSource>> createSource(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateNewsSourceRequest body,
            HttpServletRequest request) {
        long operatorId = principal(authentication).userId();
        // 幂等指纹带上 sourceCode：同键 + 空体打到两个不同来源上时，第二个会被
        // 误回放成第一个的结果（与 ADM-JOB-02 的 jobName 同一条教训）。
        CreateSourceFingerprint fingerprint = CreateSourceFingerprint.of(body);

        NewsSource created = audited(
                authentication,
                request,
                AdminAudit.NEWS_SOURCE_CREATE,
                AdminAudit.summary(
                        "sourceCode=" + body.sourceCode(),
                        AdminAudit.pair("sourceName", body.sourceName())),
                () -> idempotency.execute(
                        CREATE_SOURCE_SCOPE,
                        operatorId,
                        IdempotencyGuard.requireKey(idempotencyKey),
                        fingerprint,
                        NewsSource.class,
                        () -> sources.create(fingerprint.toCommand())));

        return ResponseEntity.status(HttpStatus.CREATED).body(success(created, request));
    }

    /** ADM-NEWS-04：修改来源（If-Match 乐观锁）。 */
    @PatchMapping("/news-sources/{sourceId}")
    @PreAuthorize("hasAuthority('news:source:update')")
    public ApiResponse<NewsSource> updateSource(
            Authentication authentication,
            @PathVariable long sourceId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody PatchNewsSourceRequest body,
            HttpServletRequest request) {
        AdminNewsSourcePatch patch = new AdminNewsSourcePatch(
                Optional.ofNullable(body.sourceName()),
                Optional.ofNullable(body.homepageUrl()),
                Optional.ofNullable(body.rightsValidFrom()),
                Optional.ofNullable(body.rightsValidTo()),
                Optional.ofNullable(body.allowAiAnalysis()),
                Optional.ofNullable(body.authorizationStatus()),
                Optional.ofNullable(body.status()));

        NewsSource updated = audited(
                authentication,
                request,
                AdminAudit.NEWS_SOURCE_UPDATE,
                AdminAudit.summary(
                        "sourceId=" + sourceId,
                        "changed=" + patchChangedFields(body)),
                () -> sources.update(sourceId, IfMatch.version(ifMatch), patch));
        return success(updated, request);
    }

    /** ADM-NEWS-05：关联分页，缺省看 CANDIDATE。 */
    @GetMapping("/news-relations")
    @PreAuthorize("hasAuthority('news:relation:list')")
    public ApiResponse<PageData<AdminNewsRelationView>> listRelations(
            @RequestParam(value = "relationStatus", required = false)
                    NewsRelationStatus relationStatus,
            @RequestParam(value = "targetType", required = false) NewsTargetType targetType,
            @RequestParam(value = "newsId", required = false) Long newsId,
            @RequestParam(value = "minConfidence", required = false) java.math.BigDecimal minConfidence,
            @RequestParam(value = "startedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startedAt,
            @RequestParam(value = "endedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endedAt,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        PageData<AdminNewsRelationView> data = relations.list(new AdminNewsRelationQuery(
                relationStatus, targetType, newsId, minConfidence,
                startedAt, endedAt, page, size));
        return success(data, request);
    }

    /** ADM-NEWS-06：人工复核候选关联（CONFIRMED 或 REJECTED）。 */
    @PatchMapping("/news-relations/{relationId}")
    @PreAuthorize("hasAuthority('news:relation:review')")
    public ApiResponse<AdminNewsRelationView> reviewRelation(
            Authentication authentication,
            @PathVariable long relationId,
            @RequestBody ReviewNewsRelationRequest body,
            HttpServletRequest request) {
        long reviewerId = principal(authentication).userId();

        AdminNewsRelationView reviewed = audited(
                authentication,
                request,
                AdminAudit.NEWS_RELATION_REVIEW,
                AdminAudit.summary(
                        "relationId=" + relationId,
                        "decision=" + (body.relationStatus() == null ? "-" : body.relationStatus()),
                        AdminAudit.pair("reason", body.reasonSummary())),
                () -> relations.review(
                        relationId, body.relationStatus(), body.reasonSummary(), reviewerId));
        return success(reviewed, request);
    }

    /** ADM-NEWS-07：手工建立可解释关联（MANUAL + CONFIRMED）。 */
    @PostMapping("/news/{newsId}/relations")
    @PreAuthorize("hasAuthority('news:relation:create')")
    public ResponseEntity<ApiResponse<AdminNewsRelationView>> createRelation(
            Authentication authentication,
            @PathVariable long newsId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateNewsRelationRequest body,
            HttpServletRequest request) {
        long operatorId = principal(authentication).userId();
        // 幂等指纹 = 新闻 + 目标：同键打到不同新闻/目标上时不能互相回放。
        CreateRelationFingerprint fingerprint =
                CreateRelationFingerprint.of(newsId, body);

        AdminNewsRelationView created = audited(
                authentication,
                request,
                AdminAudit.NEWS_RELATION_CREATE,
                AdminAudit.summary(
                        "newsId=" + newsId,
                        "targetType=" + (body.targetType() == null ? "-" : body.targetType()),
                        "targetId=" + (body.targetId() == null ? "-" : body.targetId())),
                () -> idempotency.execute(
                        CREATE_RELATION_SCOPE,
                        operatorId,
                        IdempotencyGuard.requireKey(idempotencyKey),
                        fingerprint,
                        AdminNewsRelationView.class,
                        () -> relations.createManual(
                                newsId,
                                body.targetType(),
                                body.targetId(),
                                body.reasonSummary(),
                                operatorId)));

        return ResponseEntity.status(HttpStatus.CREATED).body(success(created, request));
    }

    /** ADM-NEWS-08：删除关联 = 置 REJECTED（保留审计，不物理删除）。 */
    @DeleteMapping("/news-relations/{relationId}")
    @PreAuthorize("hasAuthority('news:relation:delete')")
    public ApiResponse<AdminNewsRelationView> deleteRelation(
            Authentication authentication,
            @PathVariable long relationId,
            @RequestBody(required = false) DeleteNewsRelationRequest body,
            HttpServletRequest request) {
        long reviewerId = principal(authentication).userId();
        String reason = body == null ? null : body.reasonSummary();

        AdminNewsRelationView deleted = audited(
                authentication,
                request,
                AdminAudit.NEWS_RELATION_DELETE,
                AdminAudit.summary(
                        "relationId=" + relationId,
                        AdminAudit.pair("reason", reason)),
                () -> relations.delete(relationId, reason, reviewerId));
        return success(deleted, request);
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

    /** 审计摘要里的"改了什么"：只记字段名，值留给业务库（契约 §22.2 的白名单口径）。 */
    private static String patchChangedFields(PatchNewsSourceRequest body) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        if (body.sourceName() != null) {
            changed.add("sourceName");
        }
        if (body.homepageUrl() != null) {
            changed.add("homepageUrl");
        }
        if (body.rightsValidFrom() != null) {
            changed.add("rightsValidFrom");
        }
        if (body.rightsValidTo() != null) {
            changed.add("rightsValidTo");
        }
        if (body.allowAiAnalysis() != null) {
            changed.add("allowAiAnalysis");
        }
        if (body.authorizationStatus() != null) {
            changed.add("authorizationStatus");
        }
        if (body.status() != null) {
            changed.add("status");
        }
        return String.join(",", changed);
    }

    // ---------- 请求体 ----------

    /** ADM-NEWS-03 的请求体。没有 {@code authorizationStatus}：它由服务端推导。 */
    public record CreateNewsSourceRequest(
            Long providerId,
            String sourceCode,
            String sourceName,
            NewsSourceType sourceType,
            String homepageUrl,
            LocalDate rightsValidFrom,
            LocalDate rightsValidTo,
            Boolean allowAiAnalysis,
            NewsSource.SourceStatus status) {
    }

    /** ADM-NEWS-04 的请求体。null = 本次不动这一项（PATCH 语义）。 */
    public record PatchNewsSourceRequest(
            String sourceName,
            String homepageUrl,
            LocalDate rightsValidFrom,
            LocalDate rightsValidTo,
            Boolean allowAiAnalysis,
            NewsSource.AuthorizationStatus authorizationStatus,
            NewsSource.SourceStatus status) {
    }

    /** ADM-NEWS-06 的请求体。 */
    public record ReviewNewsRelationRequest(
            NewsRelationStatus relationStatus,
            String reasonSummary) {
    }

    /** ADM-NEWS-07 的请求体。{@code targetId} 是对外标识（sim-600519 / sim-bk0001 / CN）。 */
    public record CreateNewsRelationRequest(
            NewsTargetType targetType,
            String targetId,
            String reasonSummary) {
    }

    /** ADM-NEWS-08 的请求体。 */
    public record DeleteNewsRelationRequest(String reasonSummary) {
    }

    /** ADM-NEWS-03 的幂等指纹：请求体加上来源编码（同 ADM-JOB-02 的 jobName）。 */
    private record CreateSourceFingerprint(
            Long providerId,
            String sourceCode,
            String sourceName,
            NewsSourceType sourceType,
            String homepageUrl,
            LocalDate rightsValidFrom,
            LocalDate rightsValidTo,
            Boolean allowAiAnalysis,
            NewsSource.SourceStatus status) {

        static CreateSourceFingerprint of(CreateNewsSourceRequest body) {
            return new CreateSourceFingerprint(
                    body.providerId(), body.sourceCode(), body.sourceName(), body.sourceType(),
                    body.homepageUrl(), body.rightsValidFrom(), body.rightsValidTo(),
                    body.allowAiAnalysis(), body.status());
        }

        NewAdminNewsSource toCommand() {
            return new NewAdminNewsSource(
                    providerId, sourceCode, sourceName, sourceType, homepageUrl,
                    rightsValidFrom, rightsValidTo,
                    allowAiAnalysis == null || allowAiAnalysis,
                    status == null ? NewsSource.SourceStatus.ACTIVE : status);
        }
    }

    /** ADM-NEWS-07 的幂等指纹：请求体加上路径上的 {@code newsId}（理由同上）。 */
    private record CreateRelationFingerprint(
            long newsId, NewsTargetType targetType, String targetId, String reasonSummary) {

        static CreateRelationFingerprint of(long newsId, CreateNewsRelationRequest body) {
            return new CreateRelationFingerprint(
                    newsId, body.targetType(), body.targetId(), body.reasonSummary());
        }
    }
}
