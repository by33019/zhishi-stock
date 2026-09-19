package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 确定性模拟限幅规则集，只收录**可核实的稳定规则**。
 *
 * <p>刻意不编码"新股上市首日不设涨跌幅"：该条款随板块与时期变化，且各板块表述不一致，
 * 无法核实到可以写进代码的程度。{@link LimitRule} 与匹配器保留了
 * {@code noPriceLimit} 与上市天数窗口的能力，等规则真正入库时无需改动匹配逻辑。
 *
 * <p>创业板 / 科创板的风险警示股涨跌幅仍为 ±20%，与普通股相同，但**必须各存一条规则**：
 * 匹配是精确等值匹配，缺一条就会出现"ST 股匹配不到任何规则"。
 */
public class SimulatedLimitRuleProvider implements LimitRuleProvider {

    /** 模拟市场中所有规则共用同一优先级，真正的区分靠静态属性与生效窗口。 */
    private static final int DEFAULT_PRIORITY = 100;

    private static final List<LimitRule> RULES = List.of(
            rule("SH-MAIN-NORMAL", "SH", "MAIN", "NORMAL", "0.10"),
            rule("SH-MAIN-ST", "SH", "MAIN", "ST", "0.05"),
            rule("SZ-MAIN-NORMAL", "SZ", "MAIN", "NORMAL", "0.10"),
            rule("SZ-MAIN-ST", "SZ", "MAIN", "ST", "0.05"),
            rule("SZ-GEM-NORMAL", "SZ", "GEM", "NORMAL", "0.20"),
            rule("SZ-GEM-ST", "SZ", "GEM", "ST", "0.20"),
            rule("SH-STAR-NORMAL", "SH", "STAR", "NORMAL", "0.20"),
            rule("SH-STAR-ST", "SH", "STAR", "ST", "0.20"),
            rule("BJ-BSE-NORMAL", "BJ", "BSE", "NORMAL", "0.30"),
            rule("BJ-BSE-ST", "BJ", "BSE", "ST", "0.30"));

    @Override
    public List<LimitRule> rules(String marketCode, LocalDate ruleDate) {
        if (!"CN".equals(marketCode)) {
            return List.of();
        }
        return RULES;
    }

    private static LimitRule rule(
            String ruleCode, String exchangeCode, String boardCode, String specialStatus, String rate) {
        BigDecimal symmetricRate = new BigDecimal(rate);
        return LimitRule.of(
                ruleCode,
                exchangeCode,
                boardCode,
                specialStatus,
                symmetricRate,
                symmetricRate,
                DEFAULT_PRIORITY);
    }
}
