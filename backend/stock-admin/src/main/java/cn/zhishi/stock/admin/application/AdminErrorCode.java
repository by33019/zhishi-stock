package cn.zhishi.stock.admin.application;

/**
 * 后台管理面的业务码与 HTTP 状态（契约 §16.4 的异常清单）。
 *
 * <p>与 {@code WatchlistErrorCode} / {@code ExportErrorCode} 同一条理由：状态码跟着业务码走，
 * 不放在 {@code GlobalExceptionHandler} 里 switch——否则新增一个码要同时改两处，
 * 而漏改的那一处不会编译失败。
 *
 * <h2>契约清单之外的三个码</h2>
 * <ul>
 *   <li>{@link #SELF_OPERATION_FORBIDDEN}：契约 ADM-USR-09 只说"禁止删除本人"，
 *       没给码。删除本人若复用 {@code LAST_SUPER_ADMIN_PROTECTED}，会把"你在删自己"
 *       说成"这是最后一个超管"——只有恰好是最后一个超管时才碰巧成立。</li>
 *   <li>{@link #PASSWORD_RESET_NO_DELIVERY_TARGET}：契约 ADM-USR-07 的 delivery 只有 EMAIL，
 *       而账号的邮箱可以为空。返回 200 + 一个空的脱敏目标会让调用方以为凭证发出去了。</li>
 *   <li>{@link #ROLE_NOT_FOUND} 与 {@link #RESOURCE_VERSION_CONFLICT} 在契约清单里，
 *       这里沿用它的名字，只补上 HTTP 状态。</li>
 * </ul>
 */
public enum AdminErrorCode {

    /** 用户不存在，或已被逻辑删除。 */
    USER_NOT_FOUND("ADMIN_USER_NOT_FOUND", 404),

    /** 账号已被占用（{@code unique_username}）。 */
    USERNAME_EXISTS("USERNAME_EXISTS", 409),

    /** 邮箱已被未删除账号占用（V2 的 {@code uk_sys_user_email}）。 */
    EMAIL_EXISTS("EMAIL_EXISTS", 409),

    /** 该操作会让系统失去最后一个启用中的超级管理员。 */
    LAST_SUPER_ADMIN_PROTECTED("LAST_SUPER_ADMIN_PROTECTED", 409),

    /** 不允许对自己执行锁定 / 改角色 / 删除。 */
    SELF_OPERATION_FORBIDDEN("ADMIN_SELF_OPERATION_FORBIDDEN", 409),

    /** {@code roleIds} 里引用了不存在或已停用的角色。 */
    ROLE_NOT_FOUND("ROLE_NOT_FOUND", 422),

    /** 请求体本身不成立（如 PATCH 没有任何可改字段、角色 id 重复）。 */
    INVALID_REQUEST("INVALID_REQUEST", 400),

    /** If-Match 的版本号与当前版本不一致。 */
    RESOURCE_VERSION_CONFLICT("RESOURCE_VERSION_CONFLICT", 409),

    /**
     * 密码重置无法投递：账号没有可用的邮箱。
     *
     * <p>不使用 200 + 空目标：那会让"凭证已生成"与"凭证送不出去"看起来是同一个结果。
     */
    PASSWORD_RESET_NO_DELIVERY_TARGET("ADMIN_PASSWORD_RESET_NO_DELIVERY_TARGET", 422),

    /** 日志不存在，或被清理策略删除。 */
    LOG_NOT_FOUND("ADMIN_LOG_NOT_FOUND", 404),

    /**
     * 操作日志的查询跨度超过 90 天上限（契约 §16.2 LOG-01）。
     *
     * <p>不复用 {@link #INVALID_REQUEST}：前端需要区分"参数写错了"和"跨度太大请收窄"，
     * 后者是可以自己重试成功的（把 {@code startedAt} 拉近），前者重试多少次都一样。
     * 两个语义共用一个码，就等于要求前端去匹配文案才敢决定要不要重试。
     */
    LOG_RANGE_TOO_WIDE("ADMIN_LOG_RANGE_TOO_WIDE", 400),

    /** 任务名不在白名单内（契约 §16.3 要求"不直接暴露任意 Handler 调用能力"）。 */
    JOB_NOT_FOUND("ADMIN_JOB_NOT_FOUND", 404),

    /** 任务存在但当前不可人工触发（未启用，或本就不支持人工触发）。 */
    JOB_NOT_TRIGGERABLE("ADMIN_JOB_NOT_TRIGGERABLE", 409),

    /** 执行记录不存在。 */
    JOB_EXECUTION_NOT_FOUND("ADMIN_JOB_EXECUTION_NOT_FOUND", 404),

    /**
     * 执行记录的状态不允许重试（契约 ADM-JOB-05 只允许对失败或部分失败重试）。
     *
     * <p>单独一个码而不是复用 {@code INVALID_REQUEST}：调用方需要知道
     * "这条记录再点一次还是会被拒"（成功/运行中/已取消），与"请求体写错了"不是一回事。
     */
    JOB_EXECUTION_NOT_RETRYABLE("ADMIN_JOB_EXECUTION_NOT_RETRYABLE", 409);

    private final String externalCode;
    private final int httpStatus;

    AdminErrorCode(String externalCode, int httpStatus) {
        this.externalCode = externalCode;
        this.httpStatus = httpStatus;
    }

    public String externalCode() {
        return externalCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
