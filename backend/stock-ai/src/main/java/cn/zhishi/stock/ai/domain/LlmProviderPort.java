package cn.zhishi.stock.ai.domain;

import java.util.function.Consumer;

/**
 * 大模型调用端口（架构 §11.3：「{@code LlmProviderPort} 由 {@code stock-integration} 实现，
 * 可替换供应商」）。
 *
 * <h2>为什么是回调而不是 {@code Stream}</h2>
 * 消费方是 SSE 中继：它拿到一段就要立刻推给浏览器。{@code Stream} 的惰性求值会让
 * "什么时候真正发起调用"变得不可预测；而端口实现（真实 HTTP 客户端）天然是
 * "读一段、回调一段"。调用方也不需要处理流关闭与异常传播的交互。
 *
 * <h2>异常语义</h2>
 * 失败一律抛 {@link LlmProviderException}，携带 {@link LlmErrorCategory} 与可重试性。
 * 端口不返回"失败结果对象"——那会让每个调用方都要判断
 * {@code result.failed()}，而漏判不会报错，只会让一份失败被当成成功。
 *
 * <p>实现必须是**线程安全**的：M3-07 的 Worker 会并发调用（全局 30 个任务）。
 * 实现不得持有可变状态（本端口的方法签名里没有会话状态，正是为此）。
 */
public interface LlmProviderPort {

    /**
     * 执行一次流式调用。
     *
     * @param request 调用请求；{@code evidenceCandidates} 是模型唯一可引用的证据集合
     * @param onChunk 每产出一个片段回调一次，顺序与产出顺序一致
     * @return 汇总结果（含全部片段与用量）
     * @throws LlmProviderException 调用失败、超时、限流或安全拒绝
     */
    LlmCompletion complete(LlmRequest request, Consumer<LlmChunk> onChunk);
}
