package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.InvalidAiContextQueryException;
import cn.zhishi.stock.ai.application.InvalidAiTargetException;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.InvalidKlineParameterException;
import cn.zhishi.stock.market.application.InvalidRankingQueryException;
import cn.zhishi.stock.market.application.InvalidSecurityQueryException;
import cn.zhishi.stock.market.application.InvalidSectorQueryException;
import cn.zhishi.stock.market.application.InvalidTurnoverParameterException;
import cn.zhishi.stock.market.application.MarketDataUnavailableException;
import cn.zhishi.stock.market.application.MarketNotFoundException;
import cn.zhishi.stock.market.application.SecurityNotFoundException;
import cn.zhishi.stock.market.application.SectorNotFoundException;
import cn.zhishi.stock.market.application.SectorQuoteNotAvailableException;
import cn.zhishi.stock.news.application.InvalidNewsQueryException;
import cn.zhishi.stock.news.application.NewsNotFoundException;
import cn.zhishi.stock.system.auth.AuthErrorCode;
import cn.zhishi.stock.system.auth.AuthException;
import cn.zhishi.stock.system.idempotency.IdempotencyKeyConflictException;
import cn.zhishi.stock.system.idempotency.IdempotencyKeyMissingException;
import cn.zhishi.stock.system.watchlist.WatchlistErrorCode;
import cn.zhishi.stock.system.watchlist.WatchlistException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MarketDataUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> marketUnavailable(
            MarketDataUnavailableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.failure(
                "MARKET_DATA_UNAVAILABLE",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(MarketNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> marketNotFound(
            MarketNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(
                "MARKET_NOT_FOUND",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> typeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                "请求参数格式无效",
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(InvalidTurnoverParameterException.class)
    public ResponseEntity<ApiResponse<Void>> invalidTurnoverParameter(
            InvalidTurnoverParameterException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(InvalidSecurityQueryException.class)
    public ResponseEntity<ApiResponse<Void>> invalidSecurityQuery(
            InvalidSecurityQueryException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * 榜单参数非法。
     *
     * <p>只覆盖"参数本身不合法"（{@code rankingType} 不在枚举内、分页越界）。
     * 筛选值在数据中不存在不属于这里——那是"没有数据"，返回空页而不是 400。
     */
    @ExceptionHandler(InvalidRankingQueryException.class)
    public ResponseEntity<ApiResponse<Void>> invalidRankingQuery(
            InvalidRankingQueryException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * K 线参数非法。
     *
     * <p>业务码由异常自身携带（{@code INVALID_REQUEST} / {@code KLINE_RANGE_TOO_LARGE} /
     * {@code ADJUSTMENT_NOT_SUPPORTED}），不在这里靠 instanceof 推断——
     * 三种情况的 HTTP 状态相同，只有业务码不同。
     */
    @ExceptionHandler(InvalidKlineParameterException.class)
    public ResponseEntity<ApiResponse<Void>> invalidKlineParameter(
            InvalidKlineParameterException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                exception.code(),
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(SecurityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> securityNotFound(
            SecurityNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(
                "SECURITY_NOT_FOUND",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /** 板块参数非法（枚举不在白名单、分页越界、日期格式错）。 */
    @ExceptionHandler(InvalidSectorQueryException.class)
    public ResponseEntity<ApiResponse<Void>> invalidSectorQuery(
            InvalidSectorQueryException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * 板块资源不可用。
     *
     * <p>业务码由异常自身携带（{@code SECTOR_NOT_FOUND} / {@code SECTOR_INACTIVE} /
     * {@code SECTOR_CONSTITUENTS_MISSING}），不在这里靠 instanceof 推断——
     * 三种情况的 HTTP 状态相同，只有业务码不同（同 {@code InvalidKlineParameterException}）。
     */
    @ExceptionHandler(SectorNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> sectorNotFound(
            SectorNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(
                exception.code(),
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /** 板块有成分但没有一条可统计的行情（全部停牌）——数据暂时拿不到，不是调用方的问题。 */
    @ExceptionHandler(SectorQuoteNotAvailableException.class)
    public ResponseEntity<ApiResponse<Void>> sectorQuoteNotAvailable(
            SectorQuoteNotAvailableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.failure(
                "SECTOR_QUOTE_NOT_AVAILABLE",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * 资讯资源不可用。
     *
     * <p>业务码由异常自身携带（{@code NEWS_NOT_FOUND} / {@code NEWS_WITHDRAWN} /
     * {@code NEWS_RIGHTS_EXPIRED} / {@code SECURITY_NOT_FOUND} / {@code SECTOR_NOT_FOUND}），
     * 不在这里靠 instanceof 推断——五种情况的 HTTP 状态相同，只有业务码不同
     * （同 {@code SectorNotFoundException}）。合并成一个 404 会让调用方无法区分
     * "链接写错了"与"内容被撤稿了"。
     */
    @ExceptionHandler(NewsNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> newsNotFound(
            NewsNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(
                exception.code(),
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * 资讯查询参数非法。
     *
     * <p>与 {@code InvalidRankingQueryException} 同一条界线：只覆盖"参数本身不合法"
     * （资讯类型不在白名单、时间格式错、分页越界、关键词过长）。筛选值在数据中不存在
     * 不属于这里——"这只证券没有资讯"返回空页，"这只证券不存在"走 404。
     */
    @ExceptionHandler(InvalidNewsQueryException.class)
    public ResponseEntity<ApiResponse<Void>> invalidNewsQuery(
            InvalidNewsQueryException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * AI 目标不合法。
     *
     * <p>业务码由异常自身携带（{@code AI_TARGET_INVALID}，契约 §13.5 的异常码表）。
     * 与下面的 {@code InvalidAiContextQueryException} 分成两个方法而不是合并：
     * "目标选错了"与"范围或场景给错了"是前端要给出不同就地提示的两件事。
     */
    @ExceptionHandler(InvalidAiTargetException.class)
    public ResponseEntity<ApiResponse<Void>> invalidAiTarget(
            InvalidAiTargetException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                exception.code(),
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * AI 上下文预览的场景或区间参数不合法。
     *
     * <p>业务码是 {@code INVALID_REQUEST}（与其它参数类异常一致）：契约 §13.5 只为
     * "目标"定义了 {@code AI_TARGET_INVALID}，为"区间不合法"新造一个 {@code AI_} 前缀的码
     * 会让前端不得不认识一个契约里没有的取值。
     */
    @ExceptionHandler(InvalidAiContextQueryException.class)
    public ResponseEntity<ApiResponse<Void>> invalidAiContextQuery(
            InvalidAiContextQueryException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                exception.code(),
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> authentication(
            AuthException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case ACCOUNT_LOCKED -> HttpStatus.LOCKED;
            case ACCOUNT_DISABLED -> HttpStatus.FORBIDDEN;
            case INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN, REFRESH_TOKEN_REUSED ->
                    HttpStatus.UNAUTHORIZED;
        };
        String externalCode = exception.code() == AuthErrorCode.INVALID_CREDENTIALS
                ? "CREDENTIALS_INVALID"
                : exception.code().name();
        return ResponseEntity.status(status).body(ApiResponse.failure(
                externalCode,
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /**
     * 自选模块的业务异常。
     *
     * <p>业务码与 HTTP 状态都由异常自身携带（{@link WatchlistErrorCode}），
     * 这里只做一件事：分组名非法时按既有约定把字段错误放进 {@code fieldErrors}。
     */
    @ExceptionHandler(WatchlistException.class)
    public ResponseEntity<ApiResponse<Object>> watchlist(
            WatchlistException exception,
            HttpServletRequest request) {
        Object data = exception.code() == WatchlistErrorCode.GROUP_NAME_INVALID
                ? new ValidationErrors(Map.of("groupName", exception.getMessage()))
                : null;
        return ResponseEntity.status(exception.code().httpStatus()).body(ApiResponse.failure(
                exception.code().externalCode(),
                exception.getMessage(),
                data,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /** 契约 §3.7：同一个幂等键配了不同请求体。 */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> idempotencyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(
                "IDEMPOTENCY_KEY_CONFLICT",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /** 契约要求必填 Idempotency-Key 的接口没带这个头。 */
    @ExceptionHandler(IdempotencyKeyMissingException.class)
    public ResponseEntity<ApiResponse<Void>> idempotencyKeyMissing(
            IdempotencyKeyMissingException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    /** 契约 §3.7：含 version 的资源提交了缺失或非法的 If-Match。 */
    @ExceptionHandler(InvalidIfMatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidIfMatch(
            InvalidIfMatchException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                exception.getMessage(),
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ValidationErrors>> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "VALIDATION_FAILED",
                "请求参数校验失败",
                new ValidationErrors(errors),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(MissingRequestCookieException.class)
    public ResponseEntity<ApiResponse<Void>> missingRefreshCookie(
            MissingRequestCookieException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure(
                "INVALID_REFRESH_TOKEN",
                "刷新令牌缺失或无效",
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST",
                "请求体格式无效",
                null,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(
            Exception exception,
            HttpServletRequest request) {
        String traceId = TraceIdFilter.current(request);
        LOGGER.error(
                "未处理的接口异常：traceId={}，type={}",
                traceId,
                exception.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.failure(
                "INTERNAL_ERROR",
                "服务暂时不可用",
                null,
                traceId,
                OffsetDateTime.now(clock)));
    }

    public record ValidationErrors(Map<String, String> fieldErrors) {
    }
}
