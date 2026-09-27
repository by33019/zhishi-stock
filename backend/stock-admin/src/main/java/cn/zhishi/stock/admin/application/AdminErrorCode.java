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
    JOB_EXECUTION_NOT_RETRYABLE("ADMIN_JOB_EXECUTION_NOT_RETRYABLE", 409),

    // ---------- 资讯治理（契约 §17.1 / §17.2）----------

    /** 资讯来源不存在。 */
    NEWS_SOURCE_NOT_FOUND("NEWS_SOURCE_NOT_FOUND", 404),

    /** 来源编码已被占用（V4 的 {@code uk_news_source_code}）。 */
    NEWS_SOURCE_CODE_EXISTS("NEWS_SOURCE_CODE_EXISTS", 409),

    /**
     * 授权区间不成立，或企图把已到期的区间标记为有效。
     *
     * <p>不复用 {@code INVALID_REQUEST}：与 {@code LOG_RANGE_TOO_WIDE} 同一条理由，
     * "把截止日期改晚一点就能成功"与"请求体本身写错了"对调用方是两种处置。
     */
    NEWS_RIGHTS_PERIOD_INVALID("NEWS_RIGHTS_PERIOD_INVALID", 400),

    /** 资讯关联不存在。 */
    NEWS_RELATION_NOT_FOUND("NEWS_RELATION_NOT_FOUND", 404),

    /**
     * 关联已被人工复核过（{@code reviewed_at} 非空），不能二次复核。
     *
     * <p>单独一个码：复核是留痕的人事动作，"覆盖上一次结论"与"改一个还没审的候选"
     * 在审计上是两种性质，前者必须显式失败让操作者意识到自己在推翻别人。
     */
    NEWS_RELATION_ALREADY_REVIEWED("NEWS_RELATION_ALREADY_REVIEWED", 409),

    /**
     * 关联目标解析不到代理键（代码打错、目标不在主数据中）。
     *
     * <p>422 而非 404：失败的是"这个请求引用的实体"，请求本身格式没问题——
     * 与 {@code ROLE_NOT_FOUND}（引用了不存在的角色）同一档。
     */
    NEWS_RELATION_TARGET_INVALID("NEWS_RELATION_TARGET_INVALID", 422),

    /** 手工关联指向的新闻不存在（ADM-NEWS-07 的 {@code newsId} 解析不到稿件）。 */
    NEWS_NOT_FOUND("NEWS_NOT_FOUND", 404),

    // ---------- AI 运营（契约 §19）----------

    /** AI 任务不存在。 */
    AI_ADMIN_TASK_NOT_FOUND("AI_ADMIN_TASK_NOT_FOUND", 404),

    /**
     * 任务已到终态，取消不再生效。
     *
     * <p>与用户侧 AI-06"如实说取消未生效"不同：管理员取消是运营动作，
     * 打到已完成的任务上是**调用方的状态判断过时**，409 让它显式暴露，
     * 而不是 200 + 一个"取消没生效"的模糊结果。
     */
    AI_ADMIN_CANCEL_NOT_ALLOWED("AI_ADMIN_CANCEL_NOT_ALLOWED", 409),

    /**
     * 用量查询的窗口跨度超上限。
     *
     * <p>与 {@code LOG_RANGE_TOO_WIDE} 同一条理由："收窄窗口重试就能成功"
     * 必须与"请求写错了"区分开。
     */
    AI_USAGE_RANGE_TOO_LARGE("AI_USAGE_RANGE_TOO_LARGE", 400);

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
