package cn.zhishi.stock.market.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 板块行情统计口径的**唯一**实现（{@code RESTful-API.md} §10 SEC-02 / SEC-03 / SEC-04 / SEC-06）。
 *
 * <p>刻意做成只依赖 {@code List<QuoteSnapshot>} 的纯函数：口径可以被逐条断言，
 * 不必先造一份 5149 只的模拟数据；SEC-02 / SEC-03 / SEC-04 三处共用同一份实现，
 * 不会各自演化出"均价算不算停牌股"这种差异。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>{@code companyCount}：全部有效成分股（<b>含停牌</b>）</li>
 *   <li>{@code averagePrice} / {@code changeRate}：非停牌且价格与涨跌幅均合法的成分股**等权**平均</li>
 *   <li>{@code tradeVolume} / {@code tradeAmount}：非停牌成分股之和</li>
 *   <li>{@code leadingStock} / {@code laggingStock}：同一集合中涨跌幅最高 / 最低者</li>
 * </ul>
 *
 * <h2>为什么停牌股计入 {@code companyCount} 却不参与统计</h2>
 * 停牌没有有效价格，把它的前收价混进均价会让板块均价失真；
 * 而"该板块有几只成分股"是成分事实，与是否停牌无关。
 *
 * <h2>为什么用等权平均而不是市值加权</h2>
 * 模拟数据源没有总股本字段，编一个股本数会让"板块涨跌幅"变成两个编造数相乘的结果。
 * 等权口径下 {@code changeRate} 就是成分股涨跌幅的平均，可被逐项复核。
 *
 * <h2>为什么量额用 {@link BigDecimal}</h2>
 * 契约把 {@code tradeVolume} 定义为 {@code integer-string}、{@code tradeAmount} 为
 * {@code decimal-string}，两者都可能超出 {@code long} 的安全表达范围（后者还带小数）。
 */
public final class SectorQuoteCalculator {

    private static final int PRICE_SCALE = 2;
    private static final int RATE_SCALE = 4;

    private SectorQuoteCalculator() {
    }

    /**
     * 计算板块统计。
     *
     * @param constituents 该板块的有效成分股快照；允许为空
     * @param dataTime     整批快照的行情时间，与 QTE-01 同源
     * @param dataStatus   整批快照的数据状态
     */
    public static SectorQuote calculate(
            Sector sector,
            List<QuoteSnapshot> constituents,
            OffsetDateTime dataTime,
            MarketOverview.DataStatus dataStatus) {
        List<QuoteSnapshot> quoted = constituents.stream()
                .filter(SectorQuoteCalculator::hasValidQuote)
                .toList();

        return new SectorQuote(
                sector.sectorId(),
                sector.sectorCode(),
                sector.sectorName(),
                sector.sectorType(),
                constituents.size(),
                average(quoted, snapshot -> decimal(snapshot.latestPrice()), PRICE_SCALE),
                average(quoted, snapshot -> decimal(snapshot.changeRate()), RATE_SCALE),
                sum(constituents, QuoteSnapshot::tradeVolume, 0).toPlainString(),
                sum(constituents, QuoteSnapshot::tradeAmount, PRICE_SCALE).toPlainString(),
                leading(quoted),
                lagging(quoted),
                dataTime,
                dataStatus);
    }

    /**
     * 成分股的**贡献度排名**（SEC-06 的 {@code contributionRank}，从 1 开始）。
     *
     * <p>有有效行情的成分股按涨跌幅降序在前，其余（停牌等无有效价格者）按
     * {@code fullSymbol} 升序续号。这样 {@code contributionRank = 1} 必然就是
     * {@link #calculate} 给出的 {@code leadingStock}——两者共用同一个"有效行情"判定
     * 与同一个比较器，是构造保证而不是约定。
     *
     * <p>它是**数据属性**，与 SEC-06 的 {@code rankingType} 无关：
     * 否则"按跌幅排序"时贡献度排名看起来会是反的。
     */
    public static Map<String, Integer> contributionRanks(List<QuoteSnapshot> constituents) {
        List<QuoteSnapshot> quoted = constituents.stream()
                .filter(SectorQuoteCalculator::hasValidQuote)
                .sorted(byChangeRateDescending())
                .toList();
        List<QuoteSnapshot> unquoted = constituents.stream()
                .filter(snapshot -> !hasValidQuote(snapshot))
                .sorted(byFullSymbol())
                .toList();

        Map<String, Integer> ranks = new HashMap<>();
        int rank = 1;
        for (QuoteSnapshot snapshot : quoted) {
            ranks.put(snapshot.security().securityId(), rank++);
        }
        for (QuoteSnapshot snapshot : unquoted) {
            ranks.put(snapshot.security().securityId(), rank++);
        }
        return Map.copyOf(ranks);
    }

    /**
     * 涨跌幅降序 + {@code fullSymbol} 升序。
     *
     * <p>{@code reversed()} 只作用于主键，因此同涨幅时仍取代码升序的第一只：
     * 若把整个比较器反转，同值项的先后会随方向翻转，"领涨股"就会随数据顺序漂移。
     */
    private static Comparator<QuoteSnapshot> byChangeRateDescending() {
        return Comparator
                .comparing((QuoteSnapshot snapshot) -> decimal(snapshot.changeRate()).orElseThrow())
                .reversed()
                .thenComparing(byFullSymbol());
    }

    /**
     * 涨跌幅升序 + {@code fullSymbol} 升序。
     *
     * <p>**不能**用 {@code byChangeRateDescending().reversed()}：那会把兜底键一起反转，
     * 于是同涨跌幅时领涨股取代码最小的、领跌股取代码最大的，"同值项先后一致"就落空了。
     */
    private static Comparator<QuoteSnapshot> byChangeRateAscending() {
        return Comparator
                .comparing((QuoteSnapshot snapshot) -> decimal(snapshot.changeRate()).orElseThrow())
                .thenComparing(byFullSymbol());
    }

    private static Comparator<QuoteSnapshot> byFullSymbol() {
        return Comparator.comparing(snapshot -> snapshot.security().fullSymbol());
    }

    /**
     * 该成分股是否可参与价格 / 涨跌幅统计。
     *
     * <p>停牌股被排除；非停牌但缺价格或缺涨跌幅的也排除——"无有效价格"是 PRD
     * §7.3 QTE-02 明确要求排除的情形，这里沿用同一判断，避免比较器去处理 {@code null}。
     */
    private static boolean hasValidQuote(QuoteSnapshot snapshot) {
        return !snapshot.security().isSuspended()
                && isDecimal(snapshot.latestPrice())
                && isDecimal(snapshot.changeRate());
    }

    /** 只累加非停牌成分股：停牌没有成交，模拟源里它带的成交量是"无意义但非零"的残留值。 */
    private static boolean countsTowardVolume(QuoteSnapshot snapshot) {
        return !snapshot.security().isSuspended();
    }

    private static String average(
            List<QuoteSnapshot> quoted, Function<QuoteSnapshot, Optional<BigDecimal>> extractor, int scale) {
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (QuoteSnapshot snapshot : quoted) {
            Optional<BigDecimal> value = extractor.apply(snapshot);
            if (value.isEmpty()) {
                continue;
            }
            total = total.add(value.get());
            count++;
        }
        if (count == 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(count), scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal sum(
            List<QuoteSnapshot> constituents, Function<QuoteSnapshot, String> extractor, int scale) {
        BigDecimal total = BigDecimal.ZERO;
        for (QuoteSnapshot snapshot : constituents) {
            if (!countsTowardVolume(snapshot)) {
                continue;
            }
            Optional<BigDecimal> value = decimal(extractor.apply(snapshot));
            if (value.isPresent()) {
                total = total.add(value.get());
            }
        }
        return total.setScale(scale, RoundingMode.HALF_UP);
    }

    private static SectorLeaderStock leading(List<QuoteSnapshot> quoted) {
        return quoted.stream().min(byChangeRateDescending()).map(SectorQuoteCalculator::leader).orElse(null);
    }

    private static SectorLeaderStock lagging(List<QuoteSnapshot> quoted) {
        return quoted.stream().min(byChangeRateAscending()).map(SectorQuoteCalculator::leader).orElse(null);
    }

    private static SectorLeaderStock leader(QuoteSnapshot snapshot) {
        return new SectorLeaderStock(
                snapshot.security(), snapshot.latestPrice(), snapshot.changeRate());
    }

    private static boolean isDecimal(String value) {
        return decimal(value).isPresent();
    }

    private static Optional<BigDecimal> decimal(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value.trim()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
