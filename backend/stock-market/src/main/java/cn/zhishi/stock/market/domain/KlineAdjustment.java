package cn.zhishi.stock.market.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * K 线复权方式（STK-07 的 {@code adjustment} 参数）。
 *
 * <p>MVP 只支持 {@code NONE}——PRD 明确"默认不复权并明确标记"，前复权、后复权
 * 与企业行动数据纳入 V1.2。枚举因此只列受支持的取值。
 *
 * <p>{@link #fromCode} 对未知取值返回空，由用例层拒绝并返回明确错误。
 * **不做静默替换**：§8.3 要求"不支持的复权方式返回明确错误而非静默替换"，
 * 调用方以为拿到前复权数据、实际拿到不复权数据，是最难排查的一类问题。
 */
public enum KlineAdjustment {

    NONE("NONE");

    private final String code;

    KlineAdjustment(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 按接口字面量解析，大小写不敏感；未知取值返回空。 */
    public static Optional<KlineAdjustment> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(adjustment -> adjustment.code.equals(normalized))
                .findFirst();
    }
}
