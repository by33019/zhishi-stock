package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.LimitRule;
import cn.zhishi.stock.market.domain.LimitRuleMatcher;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.SecurityQuote;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性模拟全市场个股行情。
 *
 * <p>规模与代码段按真实 A 股铺开（SH / SZ / BJ 三所、MAIN / GEM / STAR / BSE 四板块），
 * 共 5149 只证券。**完全确定性**：所有取值只由证券在生成序列中的序号经固定算法导出，
 * 不使用随机数、不依赖当前时间，同一输入恒定同一输出。
 *
 * <p>每只证券先定出一个**目标状态**（涨停 / 上涨 / 平盘 / 下跌 / 跌停 / 停牌），
 * 再按匹配到的限幅规则**反推价格**：涨停股的最新价恰好等于涨停价，跌停股恰好等于跌停价，
 * 涨 / 跌股严格落在限价之内。于是"生成"与"计数"互为逆运算——
 * {@link cn.zhishi.stock.market.domain.BreadthCalculator} 重新分类的结果必然与目标状态一致。
 */
public class SimulatedSecurityQuoteProvider implements SecurityQuoteProvider {

    private static final String SUPPORTED_MARKET = "CN";
    private static final String SECURITY_TYPE_STOCK = "STOCK";
    private static final String BOARD_MAIN = "MAIN";

    private static final BigDecimal MIN_PRICE_STEP = new BigDecimal("0.01");
    private static final int PREVIOUS_CLOSE_FLOOR_CENTS = 300;
    private static final int PREVIOUS_CLOSE_SPAN_CENTS = 2700;

    private static final LocalDate EARLIEST_LISTING = LocalDate.of(2010, 1, 4);
    private static final int LISTING_DAY_SPAN = 5000;

    /** 主板风险警示股占比（百分之一为单位）。 */
    private static final long RISK_WARNING_PERCENT = 3;

    /** 目标状态的占比桶：0-9 停牌、10-19 涨停、20-29 跌停、30-599 上涨、600-959 下跌、960-999 平盘。 */
    private static final long BUCKET_LIMIT = 1000;
    private static final long SUSPENDED_BOUND = 10;
    private static final long LIMIT_UP_BOUND = 20;
    private static final long LIMIT_DOWN_BOUND = 30;
    private static final long RISE_BOUND = 600;
    private static final long FALL_BOUND = 960;

    /** 代码段按真实分配规则铺开，合计 5149 只。 */
    private static final List<Block> BLOCKS = List.of(
            new Block("SH", "MAIN", "600", 0, 1000),
            new Block("SH", "MAIN", "601", 0, 300),
            new Block("SH", "MAIN", "603", 0, 200),
            new Block("SH", "MAIN", "605", 0, 100),
            new Block("SH", "STAR", "688", 0, 500),
            new Block("SZ", "MAIN", "000", 1, 999),
            new Block("SZ", "MAIN", "001", 0, 200),
            new Block("SZ", "MAIN", "002", 0, 600),
            new Block("SZ", "GEM", "300", 0, 800),
            new Block("BJ", "BSE", "430", 0, 150),
            new Block("BJ", "BSE", "830", 0, 250),
            new Block("BJ", "BSE", "870", 0, 50));

    private final LimitRuleProvider limitRuleProvider;

    public SimulatedSecurityQuoteProvider(LimitRuleProvider limitRuleProvider) {
        this.limitRuleProvider = limitRuleProvider;
    }

    @Override
    public List<SecurityQuote> fetchUniverse(String marketCode, LocalDate tradeDate) {
        if (!SUPPORTED_MARKET.equals(marketCode) || tradeDate == null) {
            return List.of();
        }
        List<LimitRule> rules = limitRuleProvider.rules(marketCode, tradeDate);
        List<SecurityQuote> universe = new ArrayList<>();
        int ordinal = 0;
        for (Block block : BLOCKS) {
            for (int offset = 0; offset < block.count(); offset++) {
                universe.add(quote(block, offset, ordinal, tradeDate, rules));
                ordinal++;
            }
        }
        return List.copyOf(universe);
    }

    private SecurityQuote quote(
            Block block, int offset, int ordinal, LocalDate tradeDate, List<LimitRule> rules) {
        String securityCode = block.prefix() + padded(offset + block.start());
        BigDecimal previousClose = previousClose(ordinal);
        SecurityQuote withoutRule = new SecurityQuote(
                SimulatedSecurityIds.securityIdOf(securityCode),
                securityCode,
                block.exchange(),
                "模拟证券" + securityCode,
                block.board(),
                SECURITY_TYPE_STOCK,
                isRiskWarning(block, ordinal),
                false,
                listedDate(ordinal),
                previousClose,
                previousClose);
        LimitRule rule = LimitRuleMatcher.match(rules, withoutRule, tradeDate).orElse(null);
        TargetState target = rule == null ? TargetState.FLAT : targetState(ordinal);
        return withoutRule
                .withLatestPrice(latestPrice(target, rule, previousClose, ordinal))
                .withSuspended(target == TargetState.SUSPENDED);
    }

    private static BigDecimal latestPrice(
            TargetState target, LimitRule rule, BigDecimal previousClose, int ordinal) {
        return switch (target) {
            case SUSPENDED, FLAT -> previousClose;
            case LIMIT_UP -> rule.limitUpPrice(previousClose);
            case LIMIT_DOWN -> rule.limitDownPrice(previousClose);
            case RISE -> insideBand(previousClose, rule.limitUpPrice(previousClose), ordinal, true);
            case FALL -> insideBand(previousClose, rule.limitDownPrice(previousClose), ordinal, false);
        };
    }

    /**
     * 在"前收"与"限价"之间取一个确定性的中间价。
     *
     * <p>涨 / 跌状态的证券必须严格落在限价之内，否则计数器会把它判成涨跌停，
     * "按目标状态反推价格"这句话就落空了。因此这里既保证至少走一分钱，也保证不顶到限价。
     */
    private static BigDecimal insideBand(
            BigDecimal previousClose, BigDecimal limitPrice, int ordinal, boolean upward) {
        BigDecimal headroom = upward
                ? limitPrice.subtract(previousClose)
                : previousClose.subtract(limitPrice);
        BigDecimal fraction = BigDecimal
                .valueOf(1 + Math.floorMod(mix(ordinal + 211L), 90L))
                .movePointLeft(2);
        BigDecimal step = headroom.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        if (step.compareTo(MIN_PRICE_STEP) < 0) {
            step = MIN_PRICE_STEP;
        }
        BigDecimal price = (upward
                ? previousClose.add(step)
                : previousClose.subtract(step)).setScale(2, RoundingMode.HALF_UP);
        if (upward && price.compareTo(limitPrice) >= 0) {
            return limitPrice.subtract(MIN_PRICE_STEP);
        }
        if (!upward && price.compareTo(limitPrice) <= 0) {
            return limitPrice.add(MIN_PRICE_STEP);
        }
        return price;
    }

    private static BigDecimal previousClose(int ordinal) {
        long spread = Math.floorMod(mix(ordinal + 101L), PREVIOUS_CLOSE_SPAN_CENTS);
        return BigDecimal.valueOf(PREVIOUS_CLOSE_FLOOR_CENTS + spread).movePointLeft(2);
    }

    private static LocalDate listedDate(int ordinal) {
        return EARLIEST_LISTING.plusDays(Math.floorMod(mix(ordinal + 313L), LISTING_DAY_SPAN));
    }

    private static boolean isRiskWarning(Block block, int ordinal) {
        return BOARD_MAIN.equals(block.board())
                && Math.floorMod(mix(ordinal * 31L + 7L), 100L) < RISK_WARNING_PERCENT;
    }

    private static TargetState targetState(int ordinal) {
        long bucket = Math.floorMod(mix(ordinal), BUCKET_LIMIT);
        if (bucket < SUSPENDED_BOUND) {
            return TargetState.SUSPENDED;
        }
        if (bucket < LIMIT_UP_BOUND) {
            return TargetState.LIMIT_UP;
        }
        if (bucket < LIMIT_DOWN_BOUND) {
            return TargetState.LIMIT_DOWN;
        }
        if (bucket < RISE_BOUND) {
            return TargetState.RISE;
        }
        if (bucket < FALL_BOUND) {
            return TargetState.FALL;
        }
        return TargetState.FLAT;
    }

    private static String padded(int value) {
        String digits = Integer.toString(value);
        return "0".repeat(3 - digits.length()) + digits;
    }

    /** SplitMix64 的收尾混合：把顺序递增的序号打散成互不相关的桶号。 */
    private static long mix(long value) {
        return SimulatedHashing.mix(value);
    }

    /** 一段连续的证券代码。 */
    private record Block(String exchange, String board, String prefix, int start, int count) {
    }

    private enum TargetState {
        SUSPENDED,
        LIMIT_UP,
        LIMIT_DOWN,
        RISE,
        FALL,
        FLAT
    }
}
