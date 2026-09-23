package cn.zhishi.stock.export.domain;

/**
 * 导出作业状态（契约 §9.2 EXP-02 的 {@code status}）。
 *
 * <h2>{@link #EXPIRED} 不是终态，而是"时间到了"</h2>
 * 它与 {@code COMPLETED} 一样是"曾经成功过"，区别只在文件还在不在。
 * 因此它不是作业推进到的一步（没有哪段代码会"执行到过期"），
 * 而是由 {@code expiresAt} 与当前时刻比出来的结论——
 * 这也是为什么过期判定只有一处：{@code 已经过了 expiresAt 吗}。
 */
public enum ExportJobStatus {

    /** 已受理、还没开始生成。EXP-01 的响应就是这个值。 */
    QUEUED,
    /** 正在生成。 */
    RUNNING,
    /** 文件已可下载。 */
    COMPLETED,
    /** 生成失败。{@code errorCode} / {@code errorMessage} 给出原因。 */
    FAILED,
    /** 文件已过保留期（默认 24 小时），记录仍在但文件不再提供。 */
    EXPIRED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED;
    }

    /** 文件此刻是否可下载。 */
    public boolean downloadable() {
        return this == COMPLETED;
    }
}
