package cn.zhishi.stock.market.domain;

import cn.zhishi.stock.market.domain.MarketOverview.BreadthData;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 把一整批个股行情按限幅规则归类，得到市场广度计数。
 *
 * <p>归类按**优先级**进行，每只证券只落入唯一一类：
 * <ol>
 *   <li>停牌 → {@code suspendedCount}</li>
 *   <li>命中规则且非不限幅，且最新价 ≥ 涨停价 → {@code riseCount} 与 {@code limitUpCount}</li>
 *   <li>命中规则且非不限幅，且最新价 ≤ 跌停价 → {@code fallCount} 与 {@code limitDownCount}</li>
 *   <li>最新价 &gt; 前收 → {@code riseCount}</li>
 *   <li>最新价 &lt; 前收 → {@code fallCount}</li>
 *   <li>其余 → {@code flatCount}</li>
 * </ol>
 *
 * <p>因此 {@code limitUpCount ⊆ riseCount}、{@code limitDownCount ⊆ fallCount}，
 * 与"涨停家数是上涨家数子集"的市场惯例一致。
 *
 * <p>涨跌停一律按**价格**而非比例判定：交易所把限价四舍五入到分后，该价格即为上限，
 * 前收 10.03 的涨停价是 11.03，涨幅只有 9.97%，按比例判定会漏掉这个涨停。
 *
 * <p>匹配不到规则的证券<b>不计入涨跌停</b>，但仍按价格计入涨 / 跌 / 平。
 * 把"无规则"当成"不限幅"会把一只 10% 上涨的普通股算成涨停，是更严重的错误；
 * 规则缺失属于数据缺口，应由规则入库补齐。
 */
public final class BreadthCalculator {

    private BreadthCalculator() {
    }

    /** 对整批个股行情计数；{@code rules} 为该市场的限幅规则全集。 */
    public static BreadthData calculate(
            List<SecurityQuote> universe, List<LimitRule> rules, LocalDate ruleDate) {
        int rise = 0;
        int fall = 0;
        int flat = 0;
        int suspended = 0;
        int limitUp = 0;
        int limitDown = 0;

        for (SecurityQuote quote : universe) {
            if (quote.suspended()) {
                suspended++;
                continue;
            }
            Optional<LimitRule> matched = LimitRuleMatcher.match(rules, quote, ruleDate)
                    .filter(LimitRule::hasPriceLimit);
            if (matched.isPresent() && hasComparablePrices(quote)) {
                LimitRule rule = matched.get();
                if (quote.latestPrice()
                        .compareTo(rule.limitUpPrice(quote.previousClosePrice())) >= 0) {
                    rise++;
                    limitUp++;
                    continue;
                }
                if (quote.latestPrice()
                        .compareTo(rule.limitDownPrice(quote.previousClosePrice())) <= 0) {
                    fall++;
                    limitDown++;
                    continue;
                }
            }
            int movement = quote.latestPrice().compareTo(quote.previousClosePrice());
            if (movement > 0) {
                rise++;
            } else if (movement < 0) {
                fall++;
            } else {
                flat++;
            }
        }
        return new BreadthData(rise, fall, flat, suspended, limitUp, limitDown);
    }

    private static boolean hasComparablePrices(SecurityQuote quote) {
        return quote.latestPrice() != null && quote.previousClosePrice() != null;
    }
}
