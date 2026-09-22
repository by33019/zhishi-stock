package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiFeedback;
import cn.zhishi.stock.ai.domain.AiFeedbackReasonCode;
import cn.zhishi.stock.ai.domain.AiFeedbackStore;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.LongSupplier;

/**
 * 报告反馈（契约 §HIS-08 / HIS-09）。
 *
 * <h2>归属校验复用报告的判据，而不是自己再写一份</h2>
 * 依赖 {@link AiReportQueryService#requireOwned}：反馈挂在报告上，能写反馈的前提与
 * 能读报告的前提必须**是同一条**。各写一份时，两条判据会分叉，而分叉的表现是
 * "读不到的报告却能给它打分"——一个不会报错的越权。
 *
 * <h2>写完之后回读</h2>
 * {@link AiFeedbackStore#upsert} 撞唯一索引时数据库保留**原有行的 id 与 created_at**，
 * 本次生成的自增 id 被丢弃。直接把入参当结果返回，调用方拿到的 {@code feedbackId}
 * 会指向一行并不存在的行——而前端接下来要用它去删反馈。
 */
public class AiFeedbackService {

    private final AiReportQueryService reports;
    private final AiFeedbackStore feedbacks;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public AiFeedbackService(
            AiReportQueryService reports,
            AiFeedbackStore feedbacks,
            LongSupplier idGenerator,
            Clock clock) {
        this.reports = reports;
        this.feedbacks = feedbacks;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 创建或替换本人对这份报告的唯一反馈（契约 §HIS-08）。
     *
     * @param feedbackTypeCode {@code HELPFUL} / {@code NOT_HELPFUL}，大小写不敏感
     * @param reasonCode       差评原因，可空；空串按"未提供"处理
     * @param detail           补充说明，可空；空串按"未提供"处理，超过 300 字符报 400
     */
    public AiFeedbackView save(
            long reportId, long userId, String feedbackTypeCode, String reasonCode, String detail) {
        reports.requireOwned(reportId, userId);

        AiFeedbackType type = AiFeedbackType.fromCode(feedbackTypeCode)
                .orElseThrow(() -> new InvalidAiFeedbackException(
                        "不支持的反馈态度：" + feedbackTypeCode + "（可选：" + AiFeedbackType.codes() + "）"));
        // 空串与 null 都表示"未提供"。不把空串存进库：那会让"没填原因"与
        // "填了一个空原因"在数据里看起来不同，而统计时又要额外判一次。
        String normalizedReason = blankToNull(reasonCode);
        AiFeedbackReasonCode reason = null;
        if (normalizedReason != null) {
            reason = AiFeedbackReasonCode.fromCode(normalizedReason)
                    .orElseThrow(() -> new InvalidAiFeedbackException(
                            "不支持的原因码：" + normalizedReason
                                    + "（可选：" + AiFeedbackReasonCode.codes() + "）"));
        }
        String normalizedDetail = blankToNull(detail);
        if (normalizedDetail != null && normalizedDetail.length() > AiFeedback.MAX_DETAIL_LENGTH) {
            // 长度上限只从领域类型取，不在这里再写一个 300。
            throw new InvalidAiFeedbackException(
                    "补充说明不得超过 " + AiFeedback.MAX_DETAIL_LENGTH + " 字符："
                            + normalizedDetail.length());
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        feedbacks.upsert(new AiFeedback(
                idGenerator.getAsLong(), reportId, userId, type, reason, normalizedDetail, now, now));

        AiFeedback stored = feedbacks.find(reportId, userId).orElseThrow(() -> new IllegalStateException(
                "反馈写入成功后读不到：reportId=" + reportId + " userId=" + userId));
        return AiFeedbackView.from(stored);
    }

    /**
     * 删除本人对这份报告的反馈（契约 §HIS-09）。
     *
     * @return 是否真的删掉了一行；本来就没有时返回 {@code false}，**不报 404**
     *     —— 契约该接口的响应字段就是 {@code deleted}，而"本来就没有"与"刚被你删掉"
     *     对调用方而言结果相同（现在都没有反馈）。报 404 会让前端把一次正常的
     *     重复点击显示成错误。
     */
    public boolean delete(long reportId, long userId) {
        reports.requireOwned(reportId, userId);
        return feedbacks.delete(reportId, userId);
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
