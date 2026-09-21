package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * AI 研究会话（对应 V6 的 {@code ai_session}）。
 *
 * <p>本轮只用到"创建会话、取会话、更新最后活动时间"三件事——HIS-01~HIS-06
 * 六个历史接口属 M3-08，届时才需要分页与筛选，本 record 不必提前长出那些字段。
 */
public record AiSession(
        long sessionId,
        long userId,
        AiScene scene,
        String title,
        String status,
        boolean favorite,
        Long lastTaskId,
        OffsetDateTime lastActivityAt,
        int version,
        OffsetDateTime createdAt) {

    /** 会话是否仍可接受追问（契约 AI-08 要求"本人活动会话"）。 */
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
