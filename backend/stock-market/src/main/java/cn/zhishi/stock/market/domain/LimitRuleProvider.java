package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 涨跌停规则来源端口。
 *
 * <p>当前唯一实现是确定性模拟实现；真实规则从 {@code stock_limit_rule} 表入库后只需替换实现类。
 */
@FunctionalInterface
public interface LimitRuleProvider {

    /** 返回指定市场、指定规则日的候选规则全集；具体哪条适用由 {@link LimitRuleMatcher} 判定。 */
    List<LimitRule> rules(String marketCode, LocalDate ruleDate);
}
