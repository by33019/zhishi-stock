package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 差评原因，取值与 {@code ai_feedback.reason_code} 的 CHECK 约束同集合。
 *
 * <p>契约 §HIS-08 只说"差评建议提供原因"，因此它是**可选**的；但这个枚举本身不做
 * "只有差评才能带原因"的限制——那是用例层的判据，因为契约没说好评带原因要报错，
 * 把策略写进枚举会让将来放宽它必须改领域类型。
 */
public enum AiFeedbackReasonCode {

    /** 事实有误。 */
    FACT_ERROR,

    /** 引用有问题（编号对不上、来源不支撑结论）。 */
    CITATION_ERROR,

    /** 过度推断：给出了证据不支持的结论。 */
    OVER_INFERENCE,

    /** 答非所问。 */
    OFF_TOPIC,

    /** 数据过期。 */
    OUTDATED;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 大小写不敏感地解析；未知取值返回空，由调用方决定是否报 400。 */
    public static Optional<AiFeedbackReasonCode> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(reason -> reason.name().equals(normalized)).findFirst();
    }
}
