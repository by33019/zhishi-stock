package cn.zhishi.stock.ai.domain;

/**
 * 队列里的一条待执行任务。
 *
 * <p>{@code messageId} 是队列自己的凭据（Redis Stream 的消息 ID），
 * 与 {@code taskId} 是两回事：同一个任务可能被投递多次（恢复扫描重投），
 * 而每次投递各有一个消息 ID，ACK 的是**这一次投递**，不是任务本身。
 * 把两者混为一谈会让"重投后 ACK 掉旧消息"变成"任务被标记为已完成"。
 *
 * @param deliveryCount 该消息被投递的次数（&gt;1 说明之前有消费者没 ACK）
 */
public record AiTaskQueueMessage(String messageId, long taskId, long deliveryCount) {
}
