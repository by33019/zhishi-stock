package cn.zhishi.stock.system.job;

/**
 * 任务触发方式（{@code job_execution_summary.trigger_type}，与
 * {@code ck_job_execution_trigger} 的取值集合逐字对应）。
 *
 * <h2>为什么按"谁触发的"分，而不是按"哪个任务"分</h2>
 * jobName 已经回答了"是什么任务"。运维要区分的是**责任归属**：
 * SCHEDULED 出的问题看调度配置，MANUAL 出的问题看是谁在什么时候按的按钮，
 * RETRY 出的问题看上一次为什么失败，RECOVERY 出的问题看上一次在哪里崩的。
 * 这四种在同一个 jobName 下会产生四条记录，必须能分开。
 *
 * <h2>RETRY 用新记录而不是覆盖</h2>
 * 时序列不可变：一次执行的结果是既成事实，覆盖它等于让"当时看到过什么"无法追溯。
 * 因此重试插入新行，{@code batch_id} 沿用原批次、{@code attempt_no} 递增
 * （{@code uk_job_execution_batch_shard_attempt} 保证不会写出两条同批同分片同次的记录）。
 */
public enum JobTriggerType {

    /** 定时调度触发。 */
    SCHEDULED,

    /** 管理员人工触发（ADM-JOB-02）。 */
    MANUAL,

    /** 管理员对失败执行的重试（ADM-JOB-05）。 */
    RETRY,

    /** 补偿扫描（如消息丢失后的恢复）。 */
    RECOVERY;

    public static JobTriggerType fromDb(String value) {
        for (JobTriggerType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的任务触发方式：" + value);
    }
}
