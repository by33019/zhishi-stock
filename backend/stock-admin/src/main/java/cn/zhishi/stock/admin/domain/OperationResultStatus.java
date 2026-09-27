package cn.zhishi.stock.admin.domain;

/**
 * 审计结果状态（契约 §16.2 LOG-01 的筛选条件）。
 *
 * <h2>取值必须与 {@code AuditEvent} 的三个常量一致</h2>
 * 写入侧用的是 {@code cn.zhishi.stock.common.audit.AuditEvent} 里的 {@code String} 常量
 * （{@code SUCCESS} / {@code FAILURE} / {@code DENIED}），读取侧用这个枚举。
 * 两者一旦漂移，表现是"按 DENIED 筛选永远返回空列表"——一个不会报错、只会骗人的结果。
 * 因此 {@code OperationLogServiceTest} 里有一条断言把两个来源钉在一起，
 * 而不是靠这里的注释提醒后来者。
 *
 * <h2>为什么读取侧要有枚举，而写入侧可以只有字符串</h2>
 * 写入侧的值由代码常量给出，拼错会编译失败；读取侧的值来自 HTTP 查询参数，
 * 拼错只会静默返回空列表。用枚举让非法取值在参数绑定阶段就变成 400。
 */
public enum OperationResultStatus {

    /** 操作成功。 */
    SUCCESS,

    /** 操作失败（如目标不存在、版本冲突）。 */
    FAILURE,

    /** 操作被拒（如超管保护、"不许操作自己"、限流）。 */
    DENIED;
}
