package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiScene;
import java.time.LocalDateTime;

/**
 * {@code ai_session LEFT JOIN ai_task} 的一行（列表投影）。
 *
 * <p>{@code lastTaskStatus} 是 join 出来的列，可能为 {@code null}：
 * 会话从未跑过任务，或 {@code last_task_id} 指向的行已不在。
 */
public record AiSessionSummaryRow(
        long sessionId,
        long userId,
        AiScene scene,
        String title,
        String status,
        boolean favorite,
        Long lastTaskId,
        String lastTaskStatus,
        LocalDateTime lastActivityAt,
        LocalDateTime createdAt,
        int version) {
}
