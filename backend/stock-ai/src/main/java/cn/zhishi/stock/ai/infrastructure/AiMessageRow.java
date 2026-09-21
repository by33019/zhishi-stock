package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiMessageRole;
import java.time.LocalDateTime;

/** {@code ai_message} 的行映射结果。 */
public record AiMessageRow(
        long messageId,
        long sessionId,
        Long taskId,
        AiMessageRole roleType,
        int sequenceNo,
        String content,
        LocalDateTime dataCutoffAt,
        LocalDateTime createdAt) {
}
