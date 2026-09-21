package cn.zhishi.stock.aiworker;

import cn.zhishi.stock.ai.application.AiTaskExecutionService;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskQueueMessage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消费 {@code stream:ai:tasks} 并执行任务。
 *
 * <h2>为什么是"定时轮询"而不是"常驻消费线程"</h2>
 * 用 {@code @Scheduled(fixedDelay)} 每轮取一批，而不是自己起一个
 * {@code while(true)} 线程：调度失败会被 Spring 的
 * {@code LOG_AND_SUPPRESS_ERROR_HANDLER} 记下来并**继续下一轮**，
 * 而自建线程里抛出的异常会让消费静默停止——症状是"任务全都卡在排队中"，
 * 且进程看起来完全健康。
 *
 * <p>{@code fixedDelay} 而不是 {@code fixedRate}：一轮里可能有若干次真实的模型调用，
 * 耗时由外部供应商决定。用 {@code fixedRate} 会在慢供应商下堆叠出并发轮次，
 * 而它们会互相争抢同一批消息。
 *
 * <h2>ACK 的判据是「这次投递已经交给执行器了」，不是「任务成功了」</h2>
 * {@link AiTaskExecutionService.Outcome#SKIPPED}（没抢到执行权 / 已是终态 / 已被取消）
 * 同样要 ACK：这些消息已经没有任何再投递的价值，留着只会让 {@code XPENDING} 一直涨。
 *
 * <p>执行中抛异常也 ACK：队列**不做 pending 重投**（见 {@code RedisAiTaskQueue}），
 * 不 ACK 并不能换来重试，只会留下一条永远不消的记录。
 * 真正负责"执行者死了怎么办"的是
 * {@link cn.zhishi.stock.ai.application.AiTaskRecoveryService 恢复扫描} 那一侧的定时扫描，
 * 它的判据是数据库里的心跳，而不是 Redis 的 pending 列表——
 * 这样"谁在做"就只有一处事实来源。
 */
@Component
public class AiTaskConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTaskConsumer.class);

    private final AiTaskQueue queue;
    private final AiTaskExecutionService executor;
    private final int batchSize;

    public AiTaskConsumer(
            AiTaskQueue queue,
            AiTaskExecutionService executor,
            @Value("${stock.ai.worker.batch-size:5}") int batchSize) {
        this.queue = queue;
        this.executor = executor;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${stock.ai.worker.poll-initial-delay-ms:1000}",
            fixedDelayString = "${stock.ai.worker.poll-delay-ms:1000}")
    public void poll() {
        List<AiTaskQueueMessage> messages = queue.receive(batchSize);
        for (AiTaskQueueMessage message : messages) {
            consume(message);
        }
    }

    /**
     * 单个消息：执行 + ACK。
     *
     * <p>捕获 {@link RuntimeException} 而不是让它冒出去：一条消息上的意外异常
     * （比如某个任务的行数据坏了）不该让同批次里其他任务一起不执行。
     * 但它**绝不静默**——记 ERROR 并带上 messageId 与 taskId，
     * 否则表现就是"某几个任务永远停在排队中"，而日志里什么都没有。
     */
    private void consume(AiTaskQueueMessage message) {
        try {
            AiTaskExecutionService.Outcome outcome = executor.execute(message.taskId());
            LOGGER.debug(
                    "AI 任务消息处理完毕：messageId={} taskId={} outcome={}",
                    message.messageId(), message.taskId(), outcome);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "AI 任务消息处理失败，交由恢复扫描兜底：messageId={} taskId={}",
                    message.messageId(), message.taskId(), exception);
        } finally {
            ack(message);
        }
    }

    /** ACK 失败不重抛：它只影响 pending 的清理，而任务的推进已经在数据库里落定了。 */
    private void ack(AiTaskQueueMessage message) {
        try {
            queue.ack(message.messageId());
        } catch (RuntimeException exception) {
            LOGGER.warn("ACK 失败（任务状态不受影响）：messageId={}", message.messageId(), exception);
        }
    }
}
