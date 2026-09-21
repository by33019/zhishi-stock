package cn.zhishi.stock.ai.domain;

/**
 * 一条待中继的任务事件。
 *
 * <p>{@code dataJson} 是**已经序列化好**的载荷，不是对象。这样做的理由有两条：
 *
 * <ol>
 *   <li>写入方（Worker 或中继自己）最清楚该事件有哪些字段，
 *       让它在写的时候定稿，避免"写时一个视图、读时再拼一个视图"两份结构；
 *   <li>中继只需原样转发，不做二次序列化——二次序列化会让时间字段的格式
 *      在中继与直读两条路径上不一致，而契约 §13.4 只定义了一种格式。
 * </ol>
 *
 * <p>{@code eventId} 复用 Redis Stream 的消息 ID（{@code 1689...-0} 这种单调递增形状），
 * 于是"事件序号"只有一处定义：它就是 Stream ID 的序号部分，不是另起的计数器。
 * 两个计数器必然分叉，而分叉的表现是"重连补发时丢了一段或重了一段"。
 */
public record AiTaskEvent(String eventId, AiTaskEventType type, String dataJson) {

    public AiTaskEvent {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不得为空");
        }
    }

    /** 是否为结束事件。 */
    public boolean terminal() {
        return type.terminal();
    }
}
