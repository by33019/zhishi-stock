package cn.zhishi.stock.market.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 版本化涨跌停规则，字段与 {@code stock_limit_rule} 表一一对应。
 *
 * <p>{@code boardCode} 为 {@code null} 表示"该交易所的默认规则"（与表注释一致）。
 * {@code effectiveTo} 为 {@code null} 表示当前有效。
 */
public record LimitRule(
        String ruleCode,
        String exchangeCode,
        String boardCode,
        String securityType,
        String specialStatus,
        Integer minListingDays,
        Integer maxListingDays,
        BigDecimal upperLimitRate,
        BigDecimal lowerLimitRate,
        boolean noPriceLimit,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int priorityNo) {

    private static final int PRICE_SCALE = 2;

    /** 常用形态：股票、无上市天数窗口、非不限幅、自 2000-01-01 起长期有效。 */
    public static LimitRule of(
            String ruleCode,
            String exchangeCode,
            String boardCode,
            String specialStatus,
            BigDecimal upperLimitRate,
            BigDecimal lowerLimitRate,
            int priorityNo) {
        return new LimitRule(
                ruleCode,
                exchangeCode,
                boardCode,
                "STOCK",
                specialStatus,
                null,
                null,
                upperLimitRate,
                lowerLimitRate,
                false,
                LocalDate.of(2000, 1, 1),
                null,
                priorityNo);
    }

    /** 该规则是否真的能算出限价；不限幅或比例缺失时为 false。 */
    public boolean hasPriceLimit() {
        return !noPriceLimit && upperLimitRate != null && lowerLimitRate != null;
    }

    /**
     * 涨停价：前收 ×（1 + 涨停比例），四舍五入到分。
     *
     * <p>交易所把四舍五入后的价格作为实际上限，因此涨跌停判定必须比对**价格**而非比例：
     * 前收 10.03 时限价是 11.03，涨幅只有 9.97%，按比例判定会漏掉这个涨停。
     */
    public BigDecimal limitUpPrice(BigDecimal previousClose) {
        requireRate(upperLimitRate);
        return previousClose
                .multiply(BigDecimal.ONE.add(upperLimitRate))
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** 跌停价：前收 ×（1 - 跌停比例），四舍五入到分。 */
    public BigDecimal limitDownPrice(BigDecimal previousClose) {
        requireRate(lowerLimitRate);
        return previousClose
                .multiply(BigDecimal.ONE.subtract(lowerLimitRate))
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static void requireRate(BigDecimal rate) {
        if (rate == null) {
            throw new IllegalStateException("不限幅规则没有涨跌停价格");
        }
    }
}
