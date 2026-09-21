package cn.zhishi.stock.ai.domain;

import java.util.Optional;

/**
 * 报告仓储（{@code ai_report}）。
 *
 * <p>{@code uk_ai_report_task} 保证一项任务最多一个报告。插入撞唯一索引
 * 意味着"同一个任务被执行了两遍"——这正是乐观锁没兜住的情况，
 * 因此这里**不吞异常**：让它抛出来，由调用方决定是记日志还是把任务标失败。
 * 静默忽略会让"任务成功但没有报告"变成一个无法解释的状态。
 */
public interface AiReportStore {

    void insert(AiReport report);

    Optional<AiReport> find(long reportId);

    Optional<AiReport> findByTask(long taskId);
}
