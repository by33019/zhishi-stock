package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.InvalidTurnoverParameterException;
import cn.zhishi.stock.market.application.MarketDataUnavailableException;
import cn.zhishi.stock.market.application.MarketNotFoundException;
import cn.zhishi.stock.system.auth.AuthErrorCode;
import cn.zhishi.stock.system.auth.AuthException;
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
