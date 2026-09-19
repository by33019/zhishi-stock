package cn.zhishi.stock.market.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 成交趋势的查询档位（MKT-04 的 {@code range} 参数）。
 *
 * <p>{@code code} 是接口对外暴露的字面量——Java 枚举常量不能以数字开头，因此
 * {@code 5D} / {@code 20D} 只能作为 code 存在，不能当作常量名。
 *
 * <p>{@code intraday} 区分两种聚合粒度：盘中按分钟聚合，跨日按日维度聚合。
 */
public enum TurnoverRange {

    TODAY("TODAY", 1, true),
    FIVE_DAYS("5D", 5, false),
    TWENTY_DAYS("20D", 20, false);

    private final String code;
    private final int tradingDayCount;
    private final boolean intraday;

    TurnoverRange(String code, int tradingDayCount, boolean intraday) {
        this.code = code;
        this.tradingDayCount = tradingDayCount;
        this.intraday = intraday;
    }

    public String code() {
        return code;
    }

    /** 需要覆盖的交易日数量；盘中档位只取当前有效的那一个交易日。 */
    public int tradingDayCount() {
        return tradingDayCount;
    }

    /** 是否为分钟粒度。 */
    public boolean intraday() {
        return intraday;
    }

    /** 按接口字面量解析，大小写不敏感；未知取值返回空。 */
    public static Optional<TurnoverRange> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(range -> range.code.equals(normalized))
                .findFirst();
    }
}
