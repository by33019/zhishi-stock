package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 会话消息（{@code ai_message}）。
 *
 * <h2>只写定稿，不写流式中间态</h2>
 * 契约 §13.4 明确"临时片段可能因最终结构、引用或安全校验失败而不形成报告"，
 * 因此 {@code chunk} 只进 Redis 事件流，**不写 {@code ai_message}**。
 * 若把片段边流边写进消息表，一次被校验拒绝的任务就会在历史里留下一段
 * 永远不会成为报告的正文，而它在 HIS-05 里看起来与正常回答没有区别。
 *
 * <h2>两个字段刻意不进 record</h2>
 * {@code content_format} 与 {@code status} 由表默认值给出（{@code MARKDOWN} / {@code COMPLETED}）：
 * 本轮每一条消息都是定稿的 Markdown。把恒定值当参数传，
 * 只会让"这两个字段其实有别的取值"变成一个无法证伪的猜测。
 *
 * @param dataCutoffAt 回答所使用数据的综合截止时间
 */
public record AiMessage(
        long messageId,
        long sessionId,
        Long taskId,
        AiMessageRole roleType,
        int sequenceNo,
        String content,
        OffsetDateTime dataCutoffAt,
        OffsetDateTime createdAt) {

    public AiMessage {
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("sequenceNo 从 1 开始：" + sequenceNo);
        }
    }
}
