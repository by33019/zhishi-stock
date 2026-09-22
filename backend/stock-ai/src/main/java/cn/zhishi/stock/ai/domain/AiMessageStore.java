package cn.zhishi.stock.ai.domain;

import java.util.List;

/**
 * 会话消息仓储（{@code ai_message}）。
 *
 * <p>{@link #nextSequenceNo} 用 {@code MAX(sequence_no) + 1} 现算，
 * 不维护独立计数器：会话内的顺序只有一处事实来源（表里的行），
 * 另起一个计数器就要处理"计数器与行不一致"的修复，而那个修复永远不会被写。
 * 并发下的撞号由 {@code uk_ai_message_session_sequence} 兜底——
 * 一次任务只写两条消息（用户问题、助手回答），撞号概率极低，
 * 而真撞上时唯一索引会明确报错，不会静默产生乱序。
 */
public interface AiMessageStore {

    /** 会话内下一个消息序号（从 1 开始）。 */
    int nextSequenceNo(long sessionId);

    void insert(AiMessage message);

    /** 某会话的消息总条数（含 {@code SYSTEM} 行）。 */
    int countBySession(long sessionId);

    /**
     * 对外可见的消息（契约 §HIS-05），按 {@code sequenceNo} 升序。
     *
     * <p>"可见"= 排除 {@code SYSTEM} 行：那些是内部 Prompt，契约明确不对外返回。
     * 排除发生在 SQL 里而不是这里过滤，理由见 {@code AiMessageMapper.VISIBLE_WHERE}。
     */
    List<AiMessage> listVisible(long sessionId, int offset, int limit);

    /** 与 {@link #listVisible} 同条件的条数，供分页字段使用。 */
    int countVisible(long sessionId);
}
