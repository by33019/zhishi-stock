package cn.zhishi.stock.system.job;

/**
 * 一次任务执行的结论。由执行体返回，由记录器写回 {@code job_execution_summary}。
 *
 * <h2>{@code counts} 可以为 null</h2>
 * 有些任务确实说不出"处理了多少条"——例如行情聚合，它的产物是一份快照，
 * 而"采集了多少条原始行情"是 Provider 内部的事，领域服务不返回。
 * 这种情况下给 {@code null}，让页面显示"计数未采集"；
 * 填 0 会让它看起来像"跑了但一条都没处理"。
 *
 * <h2>{@code errorSummary} 是脱敏摘要</h2>
 * 契约 ADM-JOB-04 明确要求"不返回第三方完整原始响应"。
 * 这个字段由执行体负责裁剪（只写平台自己的判断），记录器不做二次加工——
 * 记录器不知道哪些内容属于第三方。
 *
 * <h2>失败时 {@code status} 与异常的关系</h2>
 * 两种失败都要能表达：
 * <ul>
 *   <li>执行体正常返回但结果不理想 → {@link #partial} / {@link #failed}；</li>
 *   <li>执行体抛异常 → 记录器兜底写 {@link #failed(String, String, String)}，然后**把异常原样抛出去**。</li>
 * </ul>
 * 后者不能把异常吞掉：定时任务靠异常向上冒到 Spring 的调度器才会留下 ERROR 日志。
 */
public record JobExecutionOutcome(
        JobExecutionStatus status,
        JobExecutionCounts counts,
        String errorCategory,
        String errorCode,
        String errorSummary) {

    public JobExecutionOutcome {
        if (status == JobExecutionStatus.RUNNING) {
            throw new IllegalArgumentException("执行结论不能是 RUNNING——那是开始时的状态，不是结论");
        }
    }

    /** 成功，且不给计数。 */
    public static JobExecutionOutcome success() {
        return new JobExecutionOutcome(JobExecutionStatus.SUCCESS, null, null, null, null);
    }

    public static JobExecutionOutcome success(JobExecutionCounts counts) {
        return new JobExecutionOutcome(JobExecutionStatus.SUCCESS, counts, null, null, null);
    }

    /**
     * 部分成功。
     *
     * <p>错误摘要可以给（如"3 个来源超时"），但状态是 PARTIAL 而不是 FAILED：
     * 整轮并非失败，重试与否是运维的判断。
     */
    public static JobExecutionOutcome partial(
            JobExecutionCounts counts, String errorCategory, String errorSummary) {
        return new JobExecutionOutcome(
                JobExecutionStatus.PARTIAL, counts, errorCategory, null, errorSummary);
    }

    public static JobExecutionOutcome failed(
            String errorCategory, String errorCode, String errorSummary) {
        return new JobExecutionOutcome(
                JobExecutionStatus.FAILED, null, errorCategory, errorCode, errorSummary);
    }

    public static JobExecutionOutcome canceled(JobExecutionCounts counts) {
        return new JobExecutionOutcome(
                JobExecutionStatus.CANCELED, counts, null, null, "执行被取消");
    }

    public boolean hasCounts() {
        return counts != null;
    }
}
