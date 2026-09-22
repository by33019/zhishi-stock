package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 用户对报告的反馈态度，取值与 {@code ai_feedback.feedback_type} 的 CHECK 约束同集合。
 *
 * <p>只有"有用 / 没用"两档，没有中间态。加一档"一般"会让正负反馈率的分母口径变得可争议，
 * 而契约 §ADM-AI-06 的统计正是按正负两档算的。
 */
public enum AiFeedbackType {

    /** 有帮助。 */
    HELPFUL,

    /** 没帮助。契约建议这类反馈附带原因。 */
    NOT_HELPFUL;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 大小写不敏感地解析；未知取值返回空，由调用方决定是否报 400。 */
    public static Optional<AiFeedbackType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.name().equals(normalized)).findFirst();
    }
}
