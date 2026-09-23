package cn.zhishi.stock.export.domain;

/**
 * 一条导出审计事件（契约 §22.3："榜单和报告导出"必须写入 {@code sys_log} 并携带 {@code traceId}）。
 *
 * <p>{@code paramsSummary} 只能放**脱敏后的参数摘要**：契约 §22.2 明写
 * "日志按字段白名单记录，请求参数先脱敏"。导出请求里没有密钥，但筛选条件本身
 * 属于用户行为，因此摘要由调用方按白名单拼好再传进来，仓储不接触原始请求体。
 *
 * @param userId        发起人
 * @param username      用户名快照（用户改名后仍能对上当时的记录）
 * @param operation     操作名，如 {@code EXPORT_CREATE} / {@code EXPORT_DOWNLOAD} / {@code EXPORT_DELETE}
 * @param requestUri    请求 URI
 * @param httpMethod    HTTP 方法
 * @param resultStatus  {@code SUCCESS} / {@code FAILURE} / {@code DENIED}
 * @param paramsSummary 脱敏后的参数摘要
 * @param ip            客户端 IP
 * @param traceId       请求追踪 ID
 */
public record ExportAuditEvent(
        long userId,
        String username,
        String operation,
        String requestUri,
        String httpMethod,
        String resultStatus,
        String paramsSummary,
        String ip,
        String traceId) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    public static final String DENIED = "DENIED";
}
