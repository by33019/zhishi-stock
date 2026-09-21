package cn.zhishi.stock.ai.application;

import java.time.OffsetDateTime;

/**
 * AI-08 追问请求体（契约 §14.1 的 {@code follow-up-tasks}）。
 *
 * <p>没有 {@code scene} 与 {@code targets}：它们来自原会话，
 * 让客户端重传会让"追问是不是换了分析对象"变成一个可以悄悄发生的事。
 * 区间可以覆盖（追问可能想换个窗口），因此保留。
 *
 * <p>{@code question} 必填——没有问题的追问没有意义，而默认取上一轮的问题
 * 会让用户看到一个他没问过的分析。
 */
public record AiFollowUpRequest(
        String question, OffsetDateTime analysisStartAt, OffsetDateTime analysisEndAt) {
}
