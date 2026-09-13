package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.MarketDataUnavailableException;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
    public ResponseEntity<ApiResponse<Map<String, String>>> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "VALIDATION_FAILED",
                "请求参数校验失败",
                errors,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock)));
    }
}
