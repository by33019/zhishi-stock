package cn.zhishi.stock.admin.application;

/** 后台管理面的业务异常。业务码与 HTTP 状态都由 {@link AdminErrorCode} 携带。 */
public class AdminException extends RuntimeException {

    private final transient AdminErrorCode code;

    public AdminException(AdminErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AdminErrorCode code() {
        return code;
    }

    public static AdminException userNotFound() {
        return new AdminException(AdminErrorCode.USER_NOT_FOUND, "用户不存在或已被删除");
    }

    public static AdminException usernameExists(String username) {
        return new AdminException(AdminErrorCode.USERNAME_EXISTS, "账号已被占用：" + username);
    }

    public static AdminException emailExists(String email) {
        return new AdminException(AdminErrorCode.EMAIL_EXISTS, "邮箱已被占用：" + email);
    }

    public static AdminException lastSuperAdminProtected() {
        return new AdminException(
                AdminErrorCode.LAST_SUPER_ADMIN_PROTECTED,
                "该操作会让系统失去最后一个超级管理员，已拒绝");
    }

    public static AdminException selfOperationForbidden(String action) {
        return new AdminException(
                AdminErrorCode.SELF_OPERATION_FORBIDDEN, "不允许对当前登录账号执行" + action);
    }

    public static AdminException roleNotFound(String roleIds) {
        return new AdminException(AdminErrorCode.ROLE_NOT_FOUND, "角色不存在或已停用：" + roleIds);
    }

    public static AdminException invalidRequest(String message) {
        return new AdminException(AdminErrorCode.INVALID_REQUEST, message);
    }

    /**
     * 版本冲突。
     *
     * <p>消息带上当前版本：不带的话，前端只能靠"再看一次详情"才能重新提交，
     * 而多一次往返正是乐观锁要避免的无关代价。
     */
    public static AdminException versionConflict(int currentVersion) {
        return new AdminException(
                AdminErrorCode.RESOURCE_VERSION_CONFLICT,
                "资源已被他人修改，当前 version=" + currentVersion + "，请刷新后重试");
    }

    public static AdminException passwordResetNoDeliveryTarget() {
        return new AdminException(
                AdminErrorCode.PASSWORD_RESET_NO_DELIVERY_TARGET,
                "该账号未配置邮箱，密码重置凭证无法投递");
    }

    public static AdminException logNotFound(long logId) {
        return new AdminException(AdminErrorCode.LOG_NOT_FOUND, "日志不存在：" + logId);
    }

    /** 消息里带上限与建议值：只说"太宽了"会让调用方自己试出边界在哪。 */
    public static AdminException logRangeTooWide(int maxDays, int defaultDays) {
        return new AdminException(
                AdminErrorCode.LOG_RANGE_TOO_WIDE,
                "查询跨度不能超过 " + maxDays + " 天，未指定时默认最近 " + defaultDays + " 天");
    }

    public static AdminException jobNotFound(String jobName) {
        return new AdminException(
                AdminErrorCode.JOB_NOT_FOUND, "任务不在可管理白名单内：" + jobName);
    }

    /**
     * 消息里说明**为什么不给触发**。
     *
     * <p>"不允许人工触发"与"任务已停用"对调用方是两种不同的处置（前者改代码，后者改配置），
     * 一句笼统的"不可触发"会让运维两种都试一遍。
     */
    public static AdminException jobNotTriggerable(String jobName, String reason) {
        return new AdminException(
                AdminErrorCode.JOB_NOT_TRIGGERABLE, "任务当前不可人工触发：" + jobName + "（" + reason + "）");
    }

    public static AdminException jobExecutionNotFound(long executionId) {
        return new AdminException(
                AdminErrorCode.JOB_EXECUTION_NOT_FOUND, "任务执行记录不存在：" + executionId);
    }

    /** 带上当前状态：只说"不可重试"，调用方还得再查一次才知道它现在是什么状态。 */
    public static AdminException jobExecutionNotRetryable(long executionId, String status) {
        return new AdminException(
                AdminErrorCode.JOB_EXECUTION_NOT_RETRYABLE,
                "只有失败或部分失败的执行可以重试，执行 " + executionId + " 当前状态为 " + status);
    }
}
