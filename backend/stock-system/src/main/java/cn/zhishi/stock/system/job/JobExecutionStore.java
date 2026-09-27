package cn.zhishi.stock.system.job;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 任务执行记录的读写端口（{@code job_execution_summary}）。
 *
 * <h2>为什么这个端口在 stock-system，而不在 stock-admin</h2>
 * 写这份记录的有**两个进程**：{@code stock-backend}（管理员人工触发与重试）与
 * {@code stock-job}（定时采集）。端口放在 {@code stock-admin} 会逼着
 * {@code stock-job} 依赖整个后台域（连带 Redis 凭证、BCrypt、行情查询），
 * 而它需要的只是"往一张表写一行"。
 *
 * <p>{@code stock-job} 与 {@code stock-backend} 都已经依赖 {@code stock-system}，
 * 因此这里是两者唯一不需要新增依赖方向的落点。
 *
 * <h2>为什么没有 delete</h2>
 * 执行记录是审计材料，只增不改不删。保留策略若将来需要，会是另一个显式的清理用例，
 * 而不是让任何调用方顺手删掉一行。
 */
public interface JobExecutionStore {

    /** 插入一条 {@code RUNNING} 记录。 */
    void insert(NewJobExecution execution);

    /**
     * 写回终态（状态、计数、错误、完成时刻）。
     *
     * <p>返回受影响行数：0 行意味着这条记录已经不在或已被写过终态。
     * 调用方据此决定是"正常完成"还是"有人先动了手"，而不是假定一定成功。
     */
    boolean complete(long executionId, JobExecutionOutcome outcome, OffsetDateTime completedAt);

    /** 一页执行记录，按开始时间倒序；{@code query} 的时间范围必须是已解析的。 */
    List<JobExecution> page(JobExecutionQuery query);

    /** 同一组过滤条件下的总条数。 */
    long count(JobExecutionQuery query);

    Optional<JobExecution> find(long executionId);

    /**
     * 下一轮的尝试号：同一 {@code (jobName, batchId, shardIndex)} 下的最大尝试号 + 1。
     *
     * <p>由数据库算而不是由调用方读一次再加一：两个管理员同时点重试时，
     * "读-加一-写"会对同一个 attempt_no 竞争，而唯一索引只会让其中一个成功——
     * 另一个看到的是 {@code DuplicateKeyException}，而不是"你慢了一步"。
     */
    int nextAttemptNo(String jobName, String batchId, int shardIndex);
}
