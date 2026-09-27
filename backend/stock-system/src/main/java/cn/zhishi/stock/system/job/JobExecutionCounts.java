package cn.zhishi.stock.system.job;

/**
 * 一次任务执行的处理计数（{@code job_execution_summary} 的五个 count 列）。
 *
 * <h2>不变量：成功 + 忽略 + 失败 &le; 输入</h2>
 * 数据库有 {@code ck_job_execution_counts} 盯着这一条。放在 Java 侧再校验一次，
 * 是因为违反它的写法（比如把"去重数"既算进成功又算进忽略）在代码里看起来完全正常，
 * 而报错要等到写库那一刻、并且是一条 SQL 异常——那时原始上下文已经丢了。
 *
 * <h2>"不知道"用 {@code null} 表达，不是用 0</h2>
 * 计数列都是 {@code NOT NULL DEFAULT 0}，所以"没采集"写进库就是 0。
 * 但 0 与"不知道"在**返回给调用方**时必须是两件事：前者说"这次一条都没处理"，
 * 后者说"这次没记录计数"。因此 {@link JobExecutionOutcome#counts()} 可以为 {@code null}，
 * 而 0 是它的一种取值。列表页据此显示"计数未采集"而不是渲染一排 0
 * （一排 0 会被读成"任务跑了但什么都没干"，那是另一回事）。
 *
 * @param inputCount   输入条目数
 * @param successCount 成功条目数
 * @param ignoredCount 被忽略的条目数（幂等命中、来源不可用等"不算失败但也没处理"）
 * @param failureCount 失败条目数
 * @param outputCount  产出条目数（落库/生成的结果数，与成功数不一定相等）
 */
public record JobExecutionCounts(
        long inputCount,
        long successCount,
        long ignoredCount,
        long failureCount,
        long outputCount) {

    public JobExecutionCounts {
        long processed = successCount + ignoredCount + failureCount;
        if (processed > inputCount) {
            throw new IllegalArgumentException(
                    "成功+忽略+失败（" + processed + "）不能超过输入（" + inputCount
                            + "）：这会违反 ck_job_execution_counts");
        }
        if (inputCount < 0 || successCount < 0 || ignoredCount < 0
                || failureCount < 0 || outputCount < 0) {
            throw new IllegalArgumentException("任务计数不能为负");
        }
    }

    /** 全部为 0：任务确实跑了，但一个条目都没处理（例如来源本轮没有新内容）。 */
    public static final JobExecutionCounts ZERO = new JobExecutionCounts(0, 0, 0, 0, 0);
}
