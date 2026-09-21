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
 *       在中继与直读两条路径上不一致，而契约 §13.4 只定义了一种格式。
 * </ol>
 *
 * <h2>一个编号，三个用途</h2>
 * {@code sequence} 是**任务内序号**（{@code INCR ai:task:seq:{taskId}}，从 1 开始）。
 * 它同时是：
 * <ul>
 *   <li>SSE 帧里的 {@code id:}（契约 §13.4 的示例正是 {@code id: 38} 与 {@code sequence: 38} 相等）；
 *   <li>重连时客户端回传的 {@code Last-Event-ID}；
 *   <li>{@link AiTaskEventStream#readAfter} 的游标。
 * </ul>
 * 三者共用一个数，是刻意的：若把 Redis 的消息 ID（{@code 1689...-0} 那种毫秒时间戳形状）
 * 当作 {@code id:}，中继就必须维护一张"消息 ID → 序号"的映射才能回答
 * {@code Last-Event-ID}——那就是**第二个索引**，而两个索引必然分叉，
 * 分叉的表现是重连补发时丢了一段或重了一段，两端都不会报错。
 *
 * <p>（早先这里写着"序号就是 Stream ID 的序号部分，不是另起的计数器"，
 * 与 {@link AiTaskEventStream} 的实现正好相反。两处说法矛盾却无人察觉，
 * 因为当时还没有任何代码真的去读它。现在统一到序号这一侧。）
 */
public record AiTaskEvent(long sequence, AiTaskEventType type, String dataJson) {

    public AiTaskEvent {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不得为空");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("事件序号从 1 开始：" + sequence);
        }
    }

    /** 是否为结束事件。 */
    public boolean terminal() {
        return type.terminal();
    }
}
