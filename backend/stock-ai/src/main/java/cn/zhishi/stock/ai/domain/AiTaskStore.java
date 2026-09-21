package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 任务仓储（{@code ai_task} + {@code ai_task_target} 两张表作为一个聚合存取）。
 *
 * <h2>两条写路径，用途不同，不可互相替代</h2>
 * <ul>
 *   <li>{@link #save} 是**乐观锁**写：{@code WHERE id = ? AND version = ?}，
 *       用于"我已读过这个任务，现在把推进后的状态写回去"。冲突返回 {@code false}。
 *   <li>{@link #claimForExecution} 与 {@link #requestCancel} 是**原子条件更新**：
 *       它们的语义是"谁先到谁赢"，不能拆成"先读再写"——那样两个执行者都会认为自己赢了。
 * </ul>
 *
 * <p>把"抢执行权"做成单独的原子语句，是"至少一次消费"不会退化成"至少一次计费"的**唯一**原因。
 * 队列保证消息会被投递至少一次，但只有条件更新影响 1 行的那个实例才会调用模型。
 */
public interface AiTaskStore {

    Optional<AiTask> find(long taskId);

    /**
     * 按客户端幂等请求 ID 找任务。
     *
     * <p>用途只有一个：{@code uk_ai_task_request_id} 撞索引时，说明"同一个幂等键"
     * 曾经创建过任务（Redis 里的幂等记录丢了）。此时应当返回**已有任务**，
     * 而不是报错——契约 §3.7 要的是"重复创建不得重复消耗额度"，
     * 而不是"重复创建必须失败"。
     */
    Optional<AiTask> findByRequestId(String requestId);

    /** 插入任务及其全部目标（一个事务）。 */
    void insert(AiTask task);

    /**
     * 以乐观锁写回任务（不含目标——目标在创建时固化，之后不变）。
     *
     * <p>传入的 {@code task.version()} 是**库里当前那一行的版本**；数据库负责推进
     * （{@code SET version = version + 1}）。返回的聚合带着推进后的版本，
     * 调用方必须用它替换手里那份——继续用旧的那份写第二次本来就应该失败，
     * 而"应该失败"与"因为拿错版本而失败"在日志里长得一模一样。
     *
     * @return 写入后的任务；{@code Optional.empty()} 表示 {@code version} 已被别人推进
     *         （此时**不要**再调 LLM、也不要投递，别人已经在做了）
     */
    Optional<AiTask> save(AiTask task);

    /**
     * 抢占执行权：{@code CREATED}/{@code QUEUED} 且未被要求取消时才能抢到。
     *
     * <p>成功时原子地把 {@code status} 置为 {@code PREPARING}、{@code attempt_no + 1}，
     * 并写 {@code started_at} 与 {@code heartbeat_at}。
     *
     * @return 是否抢到；{@code false} 说明别人在做、或任务已被取消/已是终态
     */
    boolean claimForExecution(long taskId, OffsetDateTime at);

    /** 置取消意图（{@code cancel_requested = 1}），不改 {@code status}。 */
    boolean requestCancel(long taskId);

    /** 某用户的指定状态任务，按创建时间倒序。 */
    List<AiTask> findByUserAndStatuses(long userId, List<AiTaskStatus> statuses);

    /** 某用户在指定状态下的任务数（并发上限判据）。 */
    int countByUserAndStatuses(long userId, List<AiTaskStatus> statuses);

    /** 某用户在 {@code from} 之后创建的任务数（每日额度的 {@code used}）。 */
    int countCreatedSince(long userId, OffsetDateTime from);

    /**
     * 恢复扫描：找出可能失去执行者的任务。
     *
     * @param queuedBefore   {@code QUEUED} 且早于该时刻（队列消息可能丢了）
     * @param heartbeatBefore 非终态且心跳早于该时刻（执行者可能死了）
     */
    List<AiTask> findRecoverable(OffsetDateTime queuedBefore, OffsetDateTime heartbeatBefore, int limit);
}
