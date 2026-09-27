package cn.zhishi.stock.common.audit;

/**
 * 一条操作审计事件（契约 §22.3：写操作必须写入 {@code sys_log} 并携带 {@code traceId}）。
 *
 * <h2>为什么这个模型在 common 而不是某个业务域</h2>
 * 导出与后台管理都要记同一张表、同一组字段。审计若各域各写一份，
 * "什么算一次被拒绝的操作"就会有两套答案，而 {@code sys_log} 只有一张表，
 * 运维查日志时无法从行本身看出它按哪套规则写的。
 *
 * <p>{@code paramsSummary} 只能放**脱敏后的参数摘要**：契约 §22.2 明写
 * "日志按字段白名单记录，请求参数先脱敏"。摘要由调用方按白名单拼好再传进来，
 * 仓储不接触原始请求体——否则将来某个请求体里多一个字段，它就会自动进日志。
 *
 * <h2>字段可空是有意的</h2>
 * 后台异步流程（如导出生成线程）没有请求上下文：{@code requestUri} / {@code httpMethod} /
 * {@code ip} / {@code traceId} 会为 {@code null}。这比拿别的字段凑一个"看起来像"的值要好——
 * 填进去的值几个月后没人分得清它到底表示什么。
 *
 * @param userId        发起人；无登录上下文（定时任务等）时为 0
 * @param username      用户名快照（用户改名后仍能对上当时的记录）；无上下文时为 null
 * @param operation     操作名，如 {@code EXPORT_CREATE} / {@code ADMIN_USER_DELETE}
 * @param requestUri    请求 URI；非 Web 来源为 null
 * @param httpMethod    HTTP 方法；非 Web 来源为 null
 * @param resultStatus  {@code SUCCESS} / {@code FAILURE} / {@code DENIED}
 * @param paramsSummary 脱敏后的参数摘要
 * @param ip            客户端 IP；非 Web 来源为 null
 * @param traceId       请求追踪 ID；非 Web 来源为 null
 */
public record AuditEvent(
        long userId,
        String username,
        String operation,
        String requestUri,
        String httpMethod,
        String resultStatus,
        String paramsSummary,
        String ip,
        String traceId) {

    /** 操作完成。 */
    public static final String SUCCESS = "SUCCESS";

    /** 操作未完成（业务失败或未知异常）。 */
    public static final String FAILURE = "FAILURE";

    /**
     * 服务按规则**主动拒绝**了这次请求（频次超限、权限/策略不允许）。
     *
     * <p>它与 {@link #FAILURE} 分开：FAILURE 要查"哪里坏了"，DENIED 要查"谁在刷、谁越权"。
     * 两者混在一起，一段突发的拒绝就会看起来像一次故障。
     */
    public static final String DENIED = "DENIED";
}
