package cn.zhishi.stock.admin.domain;

import java.time.OffsetDateTime;

/**
 * 操作日志详情（契约 §16.2 LOG-02 的返回字段）。
 *
 * <h2>{@code paramsSummary} 是脱敏后的</h2>
 * 这里的值已经过 {@code SensitiveParamsRedactor}。类型本身不拦这件事——
 * Java 的 {@code String} 表达不了"已脱敏"——因此脱敏必须发生在仓储返回之后、
 * 用例层返回之前这一处，并且有测试盯着（历史行不保证合规，见 V2 的注释）。
 *
 * <h2>为什么要带 {@code legacyUserRef}</h2>
 * 契约要求返回它。V2 只回填了能安全匹配的历史行，剩下那些 {@code user_id} 为 NULL
 * 的行，`legacy_user_ref` 是唯一还能说明"这条日志属于谁"的线索。
 *
 * <h2>为什么带 {@code method}</h2>
 * 写入侧刻意把 {@code method} 写 NULL（见 {@code JdbcSysLogAuditLog}），
 * 但历史行里有值。读的时候照实返回，不做"反正我们没写就过滤掉"的处理——
 * 掩盖旧数据会让"这列到底有没有用"无法追溯。
 */
public record OperationLogDetail(
        long logId,
        Long userId,
        String legacyUserRef,
        String username,
        String operation,
        Integer durationMillis,
        String method,
        String requestUri,
        String httpMethod,
        String resultStatus,
        String paramsSummary,
        String ip,
        String traceId,
        OffsetDateTime createdAt) {

    /** 换掉参数摘要，其余字段不变（脱敏在用例层做，领域记录本身不知道"敏感"是什么）。 */
    public OperationLogDetail withParamsSummary(String redacted) {
        return new OperationLogDetail(
                logId, userId, legacyUserRef, username, operation, durationMillis, method,
                requestUri, httpMethod, resultStatus, redacted, ip, traceId, createdAt);
    }
}
