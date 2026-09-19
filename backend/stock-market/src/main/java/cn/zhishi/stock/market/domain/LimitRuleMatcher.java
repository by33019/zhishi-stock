package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 从候选规则中为一只证券选出**唯一适用**的限幅规则。
 *
 * <p>匹配分四步，顺序固定：
 * <ol>
 *   <li>静态属性全等：{@code exchangeCode}、{@code boardCode}、{@code securityType}、{@code specialStatus}。
 *       规则侧为 {@code null} 表示"不限"（对应 {@code stock_limit_rule} 的可空列），可匹配任意取值。</li>
 *   <li>生效窗口：{@code effectiveFrom <= ruleDate} 且（{@code effectiveTo == null} 或 {@code effectiveTo >= ruleDate}）。</li>
 *   <li>上市天数窗口：以自然日计算 {@code ChronoUnit.DAYS.between(listedDate, ruleDate)}，
 *       须落在 {@code [minListingDays, maxListingDays]} 内（两侧为 {@code null} 表示该侧不限）。</li>
 *   <li>优先级：取 {@code priorityNo} 最小者；仍并列时取 {@code ruleCode} 字典序最小者。</li>
 * </ol>
 *
 * <p>最后一步的字典序兜底不是锦上添花：若只按 {@code priorityNo} 取最小，
 * 并列时结果会取决于集合的遍历顺序，同一批数据可能算出不同的涨跌停家数。
 */
public final class LimitRuleMatcher {

    /** 风险警示股在规则中的 {@code specialStatus} 取值。 */
    private static final String SPECIAL_STATUS_RISK_WARNING = "ST";

    /** 非风险警示股在规则中的 {@code specialStatus} 取值。 */
    private static final String SPECIAL_STATUS_NORMAL = "NORMAL";

    private LimitRuleMatcher() {
    }

    /** 返回唯一适用规则；无任何规则适用时返回空。 */
    public static Optional<LimitRule> match(
            List<LimitRule> rules, SecurityQuote quote, LocalDate ruleDate) {
        if (rules == null || rules.isEmpty() || quote == null || ruleDate == null) {
            return Optional.empty();
        }
        String specialStatus = quote.st()
                ? SPECIAL_STATUS_RISK_WARNING
                : SPECIAL_STATUS_NORMAL;
        return rules.stream()
                .filter(rule -> matchesStaticAttributes(rule, quote, specialStatus))
                .filter(rule -> matchesEffectiveWindow(rule, ruleDate))
                .filter(rule -> matchesListingDayWindow(rule, quote, ruleDate))
                .min(Comparator
                        .comparingInt(LimitRule::priorityNo)
                        .thenComparing(LimitRule::ruleCode));
    }

    private static boolean matchesStaticAttributes(
            LimitRule rule, SecurityQuote quote, String specialStatus) {
        return matchesIfSpecified(rule.exchangeCode(), quote.exchangeCode())
                && matchesIfSpecified(rule.boardCode(), quote.boardCode())
                && matchesIfSpecified(rule.securityType(), quote.securityType())
                && matchesIfSpecified(rule.specialStatus(), specialStatus);
    }

    private static boolean matchesIfSpecified(String ruleValue, String actualValue) {
        return ruleValue == null || ruleValue.equals(actualValue);
    }

    private static boolean matchesEffectiveWindow(LimitRule rule, LocalDate ruleDate) {
        boolean started = rule.effectiveFrom() == null || !ruleDate.isBefore(rule.effectiveFrom());
        boolean notExpired = rule.effectiveTo() == null || !ruleDate.isAfter(rule.effectiveTo());
        return started && notExpired;
    }

    private static boolean matchesListingDayWindow(
            LimitRule rule, SecurityQuote quote, LocalDate ruleDate) {
        if (rule.minListingDays() == null && rule.maxListingDays() == null) {
            return true;
        }
        if (quote.listedDate() == null) {
            return false;
        }
        long listingDays = ChronoUnit.DAYS.between(quote.listedDate(), ruleDate);
        boolean aboveFloor = rule.minListingDays() == null
                || listingDays >= rule.minListingDays();
        boolean belowCeiling = rule.maxListingDays() == null
                || listingDays <= rule.maxListingDays();
        return aboveFloor && belowCeiling;
    }
}
