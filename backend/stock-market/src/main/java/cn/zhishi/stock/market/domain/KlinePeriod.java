package cn.zhishi.stock.market.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * K 线周期（STK-07 的 {@code period} 参数）。
 *
 * <p>{@code maxRangeYears} 承载 {@code RESTful-API.md} §8.3 的跨度上限：日 K 最多 10 年，
 * 周/月 K 最多 20 年。上限随周期而变，因此挂在枚举上而不是散落在用例层——
 * 用例层只需问"这个周期允许多长"。
 */
public enum KlinePeriod {

    DAY("DAY", 10),
    WEEK("WEEK", 20),
    MONTH("MONTH", 20);

    private final String code;
    private final int maxRangeYears;

    KlinePeriod(String code, int maxRangeYears) {
        this.code = code;
        this.maxRangeYears = maxRangeYears;
    }

    public String code() {
        return code;
    }

    /** 单次查询允许的最大跨度（年）。 */
    public int maxRangeYears() {
        return maxRangeYears;
    }

    /** 是否需要按交易日历把日 K 聚合到更大周期；{@code DAY} 直接返回日 K。 */
    public boolean aggregated() {
        return this != DAY;
    }

    /** 按接口字面量解析，大小写不敏感；未知取值返回空。 */
    public static Optional<KlinePeriod> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(period -> period.code.equals(normalized))
                .findFirst();
    }
}
