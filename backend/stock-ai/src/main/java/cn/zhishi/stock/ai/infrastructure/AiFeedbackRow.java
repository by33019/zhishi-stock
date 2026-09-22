package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiFeedbackReasonCode;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
import java.time.LocalDateTime;

/**
 * {@code ai_feedback} 的一行。
 *
 * <p>时间列用 {@link LocalDateTime} 而不是 {@code OffsetDateTime}：库里是
 * {@code datetime(3)}，不带时区；带偏移的类型在读写之间会引入一次隐式换算，
 * 而那次换算的差错只会在跨时区部署时暴露。偏移在仓储层用同一个 {@code Clock} 补上。
 */
public record AiFeedbackRow(
        long feedbackId,
        long reportId,
        long userId,
        AiFeedbackType feedbackType,
        AiFeedbackReasonCode reasonCode,
        String detail,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
