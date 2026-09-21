package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiTaskEvent;

/**
 * 事件出口（由 Web 层实现，中继本身不认识 Spring Web）。
 *
 * <p>把"往哪写"抽出来，是为了让 {@link AiTaskStreamRelay} 的循环逻辑能在
 * {@code stock-ai} 里用假实现单测——契约 §5.1 要求"用假 {@code AiTaskEventStream}
 * 断言事件序列与 {@code Last-Event-ID} 起点"，而那需要中继不依赖
 * {@code SseEmitter} 这类 Web 类型。
 */
public interface AiTaskEventSink {

    /**
     * 建连或重连后的完整状态。
     *
     * <p>它不是流里的事件（不落 Redis），因此**不带 SSE 的 {@code id:}**：
     * 给它一个 id 会让客户端的游标在收到历史补发之前先跳一次。
     */
    void sendSnapshot(String dataJson);

    /** 流里的一条事件；实现方负责把 {@code sequence} 写成 SSE 的 {@code id:}。 */
    void send(AiTaskEvent event);

    /** 客户端断开或发送失败后为 true，中继据此收尾。 */
    boolean closed();
}
