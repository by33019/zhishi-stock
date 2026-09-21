package cn.zhishi.stock.ai.domain;

import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * 任务事件流端口（SSE 中继的数据源）。
 *
 * <h2>事件序号与 Stream ID 是同一个数</h2>
 * 契约 §13.4 的示例里 {@code id: 38} 与载荷里的 {@code "sequence":38} 是同一个值，
 * 因此实现把 Stream ID 写成 {@code <sequence>-0}（显式 ID，从 1 开始）。
 * 这样三件事同时成立：
 *
 * <ul>
 *   <li>SSE 的 {@code id:} 就是序号，前端直接回传；
 *   <li>{@code Last-Event-ID: 37} 原样作为 {@code XRANGE} 的排他起点，
 *       补发语义由 Redis 保证，不需要自己维护已发送位置；
 *   <li>"序号"只有一处定义——另起一个计数器必然与 Stream 分叉，
 *       而分叉的表现是"重连后丢了一段或重了一段"，两端都不会报错。
 * </ul>
 *
 * <h2>为什么不是 Pub/Sub</h2>
 * Pub/Sub 没有历史，断线期间的片段永久丢失，重连后前端会看到一份残缺文本，
 * 而契约明确要求支持 {@code Last-Event-ID} 补发。
 */
public interface AiTaskEventStream {

    /**
     * 追加一条事件。
     *
     * <p>载荷由 {@code payloadBuilder} 在**序号确定之后**构造，因此载荷里的
     * {@code sequence} 与事件的 Stream ID 必然一致。让调用方先取序号再拼 JSON 也能实现，
     * 但那多了一次往返，且崩溃时会出现"号领了但没写"的空洞——
     * 这里的选择是"要么一起成功，要么一起没发生"。
     *
     * @param payloadBuilder 入参是本次事件的序号（从 1 开始），返回已序列化的 JSON
     * @return 已落库的事件（含 eventId 与序号）
     */
    AiTaskEvent append(long taskId, AiTaskEventType type, LongFunction<String> payloadBuilder);

    /**
     * 读取序号严格大于 {@code lastSequence} 的事件。
     *
     * @param lastSequence 已收到的最大序号；{@code 0} 表示从头补发
     */
    List<AiTaskEvent> readAfter(long taskId, long lastSequence, int count);

    /**
     * 当前最大序号。
     *
     * <p>{@code snapshot} 事件里的 {@code lastSequence} 用它：重连的客户端据此知道
     * "从哪之后是新内容"，避免把已经收到的片段再拼一遍。
     */
    Optional<Long> latestSequence(long taskId);
}
