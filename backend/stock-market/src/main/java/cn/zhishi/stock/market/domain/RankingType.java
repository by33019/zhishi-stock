package cn.zhishi.stock.market.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 榜单口径，取值与 {@code RESTful-API.md} §9.1 QTE-01 的 {@code rankingType} 对齐。
 *
 * <p>把排序口径放在枚举上而不是用例层：三种榜单的差别**只**在"按哪个字段、朝哪个方向"，
 * 写在一起就不会出现"新增一种榜单却漏改排序"的情况。
 *
 * <h2>排序键为什么必须先解析成 {@link BigDecimal}</h2>
 * 契约 §4.2 把 {@code changeRate} / {@code tradeAmount} 定义为**十进制定点字符串**。
 * 按字符串比较是错的：{@code "0.10"} 的字典序小于 {@code "0.0218"}，数值上却更大。
 * 因此排序键一律解析后比较。
 */
public enum RankingType {

    /** 涨幅榜：涨跌幅降序，最强的排最前。 */
    GAINERS("GAINERS"),

    /** 跌幅榜：涨跌幅升序，跌得最多的排最前。 */
    LOSERS("LOSERS"),

    /** 成交额榜：成交额降序，资金最活跃的排最前。 */
    TURNOVER("TURNOVER");

    private final String code;

    RankingType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 全部口径代码，顺序与声明一致。用于错误信息，避免在别处再抄一遍枚举取值。 */
    public static List<String> codes() {
        return Arrays.stream(values()).map(RankingType::code).toList();
    }

    /** 解析口径代码，大小写不敏感；未知取值返回空，**不回落默认值**。 */
    public static Optional<RankingType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.code.equals(normalized))
                .findFirst();
    }

    /**
     * 该口径所需的排序字段是否可用。
     *
     * <p>契约把 {@code changeRate} / {@code tradeAmount} 定义为可空，缺了排序键的行无法参与排序，
     * 也不该出现在榜单里——PRD §7.3 QTE-02 的「无有效价格默认排除」说的就是这种情况。
     * 先过滤再排序，比较器因此不必处理 {@code null}。
     */
    public boolean hasSortKey(QuoteSnapshot snapshot) {
        return isDecimal(keyOf(snapshot));
    }

    /**
     * 排序比较器：主键按口径取方向，兜底键恒为 {@code fullSymbol} 升序。
     *
     * <p>兜底键**不随主键方向翻转**——它的作用是让同值项有唯一且与方向无关的先后，
     * 翻转了就失去"排序稳定"的意义（PRD §7.3 QTE-02 明确要求排序稳定）。
     *
     * <p>调用前必须先用 {@link #hasSortKey} 过滤，否则排序键缺失的行会抛异常。
     */
    public Comparator<QuoteSnapshot> order() {
        Comparator<QuoteSnapshot> tieBreak =
                Comparator.comparing(snapshot -> snapshot.security().fullSymbol());
        return switch (this) {
            case GAINERS -> Comparator
                    .comparing((QuoteSnapshot snapshot) -> decimal(keyOf(snapshot)))
                    .reversed()
                    .thenComparing(tieBreak);
            case LOSERS -> Comparator
                    .comparing((QuoteSnapshot snapshot) -> decimal(keyOf(snapshot)))
                    .thenComparing(tieBreak);
            case TURNOVER -> Comparator
                    .comparing((QuoteSnapshot snapshot) -> decimal(keyOf(snapshot)))
                    .reversed()
                    .thenComparing(tieBreak);
        };
    }

    private String keyOf(QuoteSnapshot snapshot) {
        return this == TURNOVER ? snapshot.tradeAmount() : snapshot.changeRate();
    }

    private static boolean isDecimal(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("榜单排序键缺失，调用方应先用 hasSortKey 过滤");
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "榜单排序键不是合法数值，调用方应先用 hasSortKey 过滤：" + value);
        }
    }
}
