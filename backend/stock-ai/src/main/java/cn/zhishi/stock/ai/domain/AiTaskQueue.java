package cn.zhishi.stock.ai.domain;

import java.util.List;

/**
 * 待执行任务队列端口。
 *
 * <h2>为什么是「至少一次」而不是「恰好一次」</h2>
 * 契约要求任务在进程崩溃后可恢复，因此消费语义只能是至少一次：
 * 消息被取走但消费者在 ACK 前挂掉，Redis 会在超时后把它投给另一个消费者。
 * **"至少一次投递"不等于"至少一次计费"**——防重复靠的是
 * {@link AiTaskStore#claimForExecution} 的条件更新，而不是队列的投递语义。
 * 只有抢到执行权的实例才调用模型。
 *
 * <h2>为什么不用 {@code stock-job}</h2>
 * 定时采集是 cron，这里是 Consumer Group 的阻塞轮询 + ACK + 心跳恢复，
 * 两者的失败处理与可观测指标都不同（架构 §3.2 把 worker 单列为部署单元）。
 */
public interface AiTaskQueue {

    /** 投递一个任务。重复投递同一个 {@code taskId} 是允许的（恢复扫描会这么做）。 */
    void enqueue(long taskId);

    /**
     * 取一批待执行任务。
     *
     * <p>取走的消息进入 pending 状态，必须 {@link #ack} 或等待 Redis 重新投递。
     * 返回空列表表示当前没有新消息（不是错误）。
     */
    List<AiTaskQueueMessage> receive(int count);

    /** 确认一条消息已处理完（无论结果是成功还是失败——失败已写进任务状态）。 */
    void ack(String messageId);
}
