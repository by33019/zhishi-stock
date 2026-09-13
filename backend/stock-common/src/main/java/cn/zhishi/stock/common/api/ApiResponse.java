package cn.zhishi.stock.common.api;

import java.time.OffsetDateTime;

public record ApiResponse<T>(
    boolean success,
    String code,
    String message,
    T data,
    String traceId,
    OffsetDateTime timestamp) {

  public static <T> ApiResponse<T> success(T data, String traceId, OffsetDateTime timestamp) {
    return new ApiResponse<>(true, "SUCCESS", "操作成功", data, traceId, timestamp);
  }

  public static <T> ApiResponse<T> failure(
      String code, String message, T data, String traceId, OffsetDateTime timestamp) {
    return new ApiResponse<>(false, code, message, data, traceId, timestamp);
  }
}
