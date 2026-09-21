package cn.zhishi.stock.aiworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.application.AiTaskExecutionService;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskQueueMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 消费者测试。
 *
 * <p>它守着一类**只会表现为"任务卡住"**的缺陷：消息被取走但没有 ACK，
 * 于是它永远留在 pending 里；以及一条消息上的意外异常让同批次其他任务
 * 一起不执行——后者在日志里只有一条栈，看起来像"只有这一个任务失败了"。
 */
class AiTaskConsumerTest {

    private final RecordingQueue queue = new RecordingQueue();
    private final AiTaskExecutionService executor = mock(AiTaskExecutionService.class);

    /** 执行器收到的 taskId 顺序。用 answer 记录，而不是让队列桩去记。 */
    private final List<Long> executed = new ArrayList<>();

    private AiTaskConsumer consumer() {
        return new AiTaskConsumer(queue, executor, 5);
    }

    @Test
    @DisplayName("一轮把取到的每条消息都执行一次并 ACK 一次")
    void executesAndAcksEveryReceivedMessage() {
        queue.received = List.of(message("1-0", 101L), message("2-0", 102L));
        when(executor.execute(anyLong())).thenAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return AiTaskExecutionService.Outcome.COMPLETED;
        });

        consumer().poll();

        assertThat(executed).containsExactly(101L, 102L);
        assertThat(queue.acked).containsExactly("1-0", "2-0");
    }

    /**
     * {@code SKIPPED} 表示"别人抢到了执行权 / 已是终态 / 已被取消"。
     *
     * <p>这三种情况都必须 ACK：消息已经没有任何再投递的价值，
     * 留着只会让 {@code XPENDING} 一直涨，而"谁在做"的事实来源是数据库。
     */
    @Test
    @DisplayName("没抢到执行权（SKIPPED）也要 ACK，不留在 pending 里")
    void acksSkippedMessagesToo() {
        queue.received = List.of(message("1-0", 101L));
        when(executor.execute(anyLong())).thenReturn(AiTaskExecutionService.Outcome.SKIPPED);

        consumer().poll();

        assertThat(queue.acked).containsExactly("1-0");
    }

    /**
     * 一条消息上的意外异常不该让同批次其他任务一起不执行。
     *
     * <p>但 ACK 仍然要做：队列**不做 pending 重投**，不 ACK 换不来重试，
     * 只会留下一条永不消掉的记录。兜底由恢复扫描按数据库心跳负责。
     */
    @Test
    @DisplayName("单个任务抛异常：不影响同批次其他任务，且它自己仍被 ACK")
    void keepsGoingWhenOneMessageFails() {
        queue.received = List.of(message("1-0", 101L), message("2-0", 102L));
        when(executor.execute(101L)).thenThrow(new IllegalStateException("行数据坏了"));
        when(executor.execute(102L)).thenAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return AiTaskExecutionService.Outcome.COMPLETED;
        });

        assertThatCode(() -> consumer().poll()).doesNotThrowAnyException();

        assertThat(executed).containsExactly(102L);
        assertThat(queue.acked).containsExactly("1-0", "2-0");
    }

    @Test
    @DisplayName("队列为空：不执行、不 ACK")
    void doesNothingWhenQueueIsEmpty() {
        consumer().poll();

        assertThat(executed).isEmpty();
        assertThat(queue.acked).isEmpty();
    }

    private static AiTaskQueueMessage message(String messageId, long taskId) {
        return new AiTaskQueueMessage(messageId, taskId, 1L);
    }

    private static final class RecordingQueue implements AiTaskQueue {

        List<AiTaskQueueMessage> received = List.of();
        final List<String> acked = new ArrayList<>();

        @Override
        public void enqueue(long taskId) {
        }

        @Override
        public List<AiTaskQueueMessage> receive(int count) {
            return received;
        }

        @Override
        public void ack(String messageId) {
            acked.add(messageId);
        }
    }
}
