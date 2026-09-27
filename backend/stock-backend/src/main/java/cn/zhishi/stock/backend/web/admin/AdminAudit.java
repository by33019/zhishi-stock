package cn.zhishi.stock.backend.web.admin;

import cn.zhishi.stock.admin.application.AdminErrorCode;
import cn.zhishi.stock.admin.application.AdminException;
import cn.zhishi.stock.common.audit.AuditEvent;

/**
 * 后台写操作的审计口径（契约 §22.3 + 计划里的操作名清单）。
 *
 * <h2>操作名集中在一处</h2>
 * 后台的写操作各有一个 {@code operation} 常量（用户管理 7 个 + 任务管理 2 个）。
 * 散在各个控制器里，改名或复制粘贴
 * 会让同一个业务动作在 {@code sys_log} 里出现两种写法，而"按操作名筛选日志"正是
 * 运维最先做的事。
 *
 * <h2>"被拒"的定义是各域自己的知识</h2>
 * 后台只有两类拒绝：超管保护与"不许操作自己"，加上"任务名不在白名单内"。
 * 其余（账号重复、版本冲突、目标不存在）都是"这次操作没做成"，运维要看的是哪里坏了，
 * 不是谁在越权。与导出侧的口径（限流算拒绝）同类，但**判定写在各自的域里**——
 * 一个统一的"什么算拒绝"会让两域共用一条与它们都无关的规则。
 */
final class AdminAudit {

    static final String USER_CREATE = "ADMIN_USER_CREATE";
    static final String USER_UPDATE = "ADMIN_USER_UPDATE";
    static final String USER_STATUS_CHANGE = "ADMIN_USER_STATUS_CHANGE";
    static final String USER_ROLE_REPLACE = "ADMIN_USER_ROLE_REPLACE";
    static final String USER_PASSWORD_RESET = "ADMIN_USER_PASSWORD_RESET";
    static final String USER_SESSION_REVOKE = "ADMIN_USER_SESSION_REVOKE";
    static final String USER_DELETE = "ADMIN_USER_DELETE";

    static final String JOB_TRIGGER = "ADMIN_JOB_TRIGGER";
    static final String JOB_RETRY = "ADMIN_JOB_RETRY";

    static final String NEWS_SOURCE_CREATE = "ADMIN_NEWS_SOURCE_CREATE";
    static final String NEWS_SOURCE_UPDATE = "ADMIN_NEWS_SOURCE_UPDATE";
    static final String NEWS_RELATION_REVIEW = "ADMIN_NEWS_RELATION_REVIEW";
    static final String NEWS_RELATION_CREATE = "ADMIN_NEWS_RELATION_CREATE";
    static final String NEWS_RELATION_DELETE = "ADMIN_NEWS_RELATION_DELETE";

    static final String AI_TASK_CANCEL = "ADMIN_AI_TASK_CANCEL";

    /** 摘要长度上限：{@code reason} 是自由文本，不设上限就能把一行日志撑成一篇文档。 */
    private static final int SUMMARY_LIMIT = 200;

    private AdminAudit() {
    }

    static String failureStatus(RuntimeException exception) {
        if (exception instanceof AdminException admin) {
            AdminErrorCode code = admin.code();
            if (code == AdminErrorCode.LAST_SUPER_ADMIN_PROTECTED
                    || code == AdminErrorCode.SELF_OPERATION_FORBIDDEN
                    || code == AdminErrorCode.JOB_NOT_FOUND) {
                return AuditEvent.DENIED;
            }
        }
        return AuditEvent.FAILURE;
    }

    /**
     * 参数摘要（契约 §22.2：按字段白名单记录、请求参数先脱敏）。
     *
     * <p><b>密码永不入日志</b>：白名单由调用方逐个字段拼出来，而不是把请求体
     * 序列化进摘要——后者会让将来新增的任何字段自动进日志，而 {@code sys_user}
     * 相关的请求体里迟早会出现敏感项。
     */
    static String summary(String... pairs) {
        StringBuilder builder = new StringBuilder();
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(pair);
        }
        return builder.length() <= SUMMARY_LIMIT
                ? builder.toString()
                : builder.substring(0, SUMMARY_LIMIT) + "…";
    }

    /** 自由文本字段（如 {@code reason}）的归一化：换行会破坏一行一条的日志格式。 */
    static String text(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 自由文本字段的 {@code 键=值} 片段；没有值时返回 {@code null}，由 {@link #summary} 整项丢掉。
     *
     * <p><b>不能写成 {@code "reason=" + text(value)}</b>：Java 的字符串拼接会把 null
     * 变成字面量 {@code "null"}，于是日志里出现 {@code reason=null}——
     * 一个看起来像"原因就是 null 这个词"的事实，而真相是调用方根本没给原因。
     * 运维按 {@code reason} 检索时会因为这一项永远存在而得出错误的结论。
     */
    static String pair(String key, String value) {
        String text = text(value);
        return text == null || text.isEmpty() ? null : key + '=' + text;
    }
}
