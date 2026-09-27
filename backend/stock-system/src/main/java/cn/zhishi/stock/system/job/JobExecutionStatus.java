package cn.zhishi.stock.system.job;

/**
 * 任务执行状态（{@code job_execution_summary.status}，与
 * {@code ck_job_execution_status} 的取值集合逐字对应）。
 *
 * <h2>为什么要有 PARTIAL</h2>
 * 采集类任务的常态是"大部分成功、少数条目失败"（某家来源超时、某条记录被 CHECK 拒绝）。
 * 只有成功/失败两态时，这种执行只能记为 FAILED——于是"整体健康但有零星问题"与
 * "整轮什么都没拿到"在运维眼里长得一样，而它们的处置方式完全不同。
 *
 * <h2>为什么要有 CANCELED</h2>
 * {@code RUNNING} 的任务被管理员取消时，它既不是成功也不是失败。
 * 记为 FAILED 会让"失败率"这个指标把人为干预也算进去。
 *
 * <h2>可重试的判据在这里，不在调用方</h2>
 * 契约 ADM-JOB-05 只允许对失败或部分失败重试。把 {@link #isRetryable()} 写在这里，
 * 是为了让"哪些状态能重试"只有一个答案——写在用例层，第二次用到时就会有人凭印象再写一遍。
 */
public enum JobExecutionStatus {

    /** 已开始、尚未结束。 */
    RUNNING,

    /** 全部处理成功。 */
    SUCCESS,

    /** 部分成功（有失败或被忽略的条目，但整轮并非失败）。 */
    PARTIAL,

    /** 整轮失败。 */
    FAILED,

    /** 被取消（人工或其他调度器）。 */
    CANCELED;

    public boolean isTerminal() {
        return this != RUNNING;
    }

    /** 契约 ADM-JOB-05：只有失败与部分失败可以重试。 */
    public boolean isRetryable() {
        return this == FAILED || this == PARTIAL;
    }

    /**
     * 从数据库取值。
     *
     * <p>未知值直接抛：{@code status} 参与重试判定，猜一个默认值会让"这行到底怎么了"
     * 变成不可知。{@code ck_job_execution_status} 保证列里只可能是这五个值。
     */
    public static JobExecutionStatus fromDb(String value) {
        for (JobExecutionStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的任务执行状态：" + value);
    }
}
