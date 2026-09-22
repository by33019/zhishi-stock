package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 用户对一份报告的反馈（{@code ai_feedback}）。
 *
 * <h2>为什么是"一份报告一条"</h2>
 * V6 的 {@code uk_ai_feedback_report_user} 把 {@code (report_id, user_id)} 设为唯一，
 * 因此契约 §HIS-08 的"创建或替换本人对报告的唯一反馈"在库层面就是**同一行的更新**，
 * 而不是插一条新的。历史反馈不进库是有意的：契约 §HIS-09 说删除反馈"不影响报告和
 * 内部聚合历史"，而聚合是 M3-11 的 ADM-AI-06 从这张表做的——保留多条会让"这份报告的
 * 正负反馈率"口径变成可争议的。
 *
 * <h2>detail 上限只定义一次</h2>
 * {@link #MAX_DETAIL_LENGTH} 同时被这里与用例层的校验使用，并与
 * {@code ck_ai_feedback_detail} 的 300 对齐。三处各写一个 300 必然漂移，
 * 而漂移的表现是"应用层放过、数据库拒绝"，报出来是一句看不懂的约束错误。
 */
public record AiFeedback(
        long feedbackId,
        long reportId,
        long userId,
        AiFeedbackType feedbackType,
        AiFeedbackReasonCode reasonCode,
        String detail,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 详情最大长度，与 {@code ck_ai_feedback_detail} 同口径。 */
    public static final int MAX_DETAIL_LENGTH = 300;

    public AiFeedback {
        if (feedbackType == null) {
            throw new IllegalArgumentException("feedbackType 不得为空");
        }
        if (detail != null && detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "detail 不得超过 " + MAX_DETAIL_LENGTH + " 字符：" + detail.length());
        }
    }
}
