package cn.zhishi.stock.ai.domain;

import java.util.List;

/**
 * 大模型调用用量仓储（{@code ai_usage}）。
 *
 * <h2>只增不改，是台账不是状态</h2>
 * 只提供 {@code insert}：一行代表"有一次调用真的发生了，花了这些 token、用了这些时间"，
 * 这是已经发生的事实，没有"改"的语义。刻意不提供 {@code update} / {@code delete}——
 * 一旦可以改，成本汇总就失去了它唯一的价值（可审计）。任务被取消或重试都不会
 * 抹掉已发生的开销，正是要的效果。
 *
 * <p>单条写入而不是整批：一次执行只发起一次调用，批量的唯一收益（省往返）
 * 在这里不存在；而提供一个 {@code insertAll} 只会诱使调用方把多次不同任务的调用
 * 凑成一批，那时"哪一行属于哪次尝试"就更难看清了。
 *
 * <h2>幂等由数据库兜底</h2>
 * {@code uk_ai_usage_task_attempt} 保证同一任务的同一执行次数只有一行。
 * 执行次数在**抢占执行权时**递增（{@code attempt_no = attempt_no + 1}），
 * 而一次抢占只发起一次调用，所以恢复扫描重跑时用的是新的 {@code attempt_no}，
 * 不会撞键。
 */
public interface AiUsageStore {

    /** 追加一行调用用量。 */
    void insert(AiUsage usage);

    /** 某任务的全部调用记录，按执行次数升序。没有记录时返回空列表。 */
    List<AiUsage> listByTask(long taskId);
}
