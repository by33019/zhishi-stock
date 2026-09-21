package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiScene;
import java.time.LocalDateTime;

/**
 * {@code ai_session} 的行映射结果。
 *
 * <p>不含 {@code deleted_at} / {@code purge_after}：软删除属 HIS-04（M3-08），
 * 本轮不写这两列。V6 的 {@code ck_ai_session_delete_state} 保证
 * {@code status <> 'DELETED'} 时它们必须为 {@code NULL}，因此不写是自洽的。
 */
public record AiSessionRow(
        long sessionId,
        long userId,
        AiScene scene,
        String title,
        String status,
        boolean favorite,
        Long lastTaskId,
        LocalDateTime lastActivityAt,
        int version,
        LocalDateTime createdAt) {
}
