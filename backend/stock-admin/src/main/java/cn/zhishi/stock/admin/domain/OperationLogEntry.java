package cn.zhishi.stock.admin.domain;

import java.time.OffsetDateTime;

/**
 * 操作日志列表的一行（契约 §16.2 LOG-01 的返回字段）。
 *
 * <h2>没有 params</h2>
 * 列表不返回参数摘要：一页 20 行、每行一段自由文本，既撑大响应，也让"列表接口"
 * 变成事实上的详情接口。要看摘要走 LOG-02。
 *
 * <h2>可空字段是真实存在的</h2>
 * {@code userId} 可空，因为 V2 只回填了能安全匹配的历史行（{@code legacy_user_ref}）；
 * {@code durationMillis} 可空，因为这一列是旧结构留下的，应用侧从来没写过。
 * 把它们写成 {@code long} / {@code int} 会逼着某一层编一个 0 出来，
 * 而 0 毫秒与"没有这项事实"在运维眼里是两件事。
 */
public record OperationLogEntry(
        long logId,
        Long userId,
        String username,
        String operation,
        Integer durationMillis,
        String requestUri,
        String httpMethod,
        String resultStatus,
        String ip,
        String traceId,
        OffsetDateTime createdAt) {
}
