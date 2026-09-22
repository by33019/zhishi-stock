package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiFeedback;
import java.time.OffsetDateTime;

/**
 * 反馈视图，HIS-06 内嵌的「当前用户反馈」与 HIS-08 的响应体**共用**同一个类型。
 *
 * <h2>为什么两处共用一个类型</h2>
 * 报告详情里嵌的反馈与单独取反馈，本该是同一份事实。分成两个类型时，
 * 加了字段只改一处、另一处静默少一个字段——而前端两处渲染的是同一条数据，
 * 表现是"从详情页看有原因、从反馈接口看没有"。
 *
 * <h2>为什么带上 {@code feedbackId}</h2>
 * HIS-06 只需要展示，但展示之后用户接着会点"取消评价"，那就需要 id。
 * 内嵌视图不给 id 会逼前端再发一次请求去拿它，而它本来就在同一次响应里。
 *
 * @param feedbackId  反馈 ID（Snowflake 字符串，同项目「业务 ID 一律字符串」的约定）
 * @param reasonCode  差评原因，未提供时为 {@code null}
 * @param detail      补充说明，未提供时为 {@code null}
 */
public record AiFeedbackView(
        String feedbackId,
        String feedbackType,
        String reasonCode,
        String detail,
        OffsetDateTime updatedAt) {

    public static AiFeedbackView from(AiFeedback feedback) {
        return new AiFeedbackView(
                Long.toString(feedback.feedbackId()),
                feedback.feedbackType().name(),
                feedback.reasonCode() == null ? null : feedback.reasonCode().name(),
                feedback.detail(),
                feedback.updatedAt());
    }
}
