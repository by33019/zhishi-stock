package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.KlineAdjustment;
import cn.zhishi.stock.market.domain.KlinePeriod;
import cn.zhishi.stock.market.domain.KlineProvider;
import cn.zhishi.stock.market.domain.KlineRequest;
import cn.zhishi.stock.market.domain.KlineSeries;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotProvider;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * M2-05 用例：个股快照（STK-04）与日/周/月 K 线（STK-07）。
 *
 * <p>参数校验集中在用例层，Provider 只负责取数：
 * <ul>
 *   <li>{@code period} 必填，取值 {@code DAY} / {@code WEEK} / {@code MONTH}；</li>
 *   <li>{@code adjustment} 可选，缺省 {@code NONE}，**其他取值一律报错而非静默替换**
 *       （§8.3 明确要求"不支持的复权方式返回明确错误而非静默替换"）；</li>
 *   <li>{@code startDate} / {@code endDate} 可选，缺省为**最近 120 个交易日**；</li>
 *   <li>跨度上限随周期而变（日 K 10 年、周/月 K 20 年），挂在 {@link KlinePeriod} 上。</li>
 * </ul>
 *
 * <p>「证券不存在」与「市场不受支持」在端口层都表现为返回空，用例层统一转成 404。
 * 这与筛选值不存在时的处理不同——那时空结果是诚实的；而这里请求的是一个**具体资源**，
 * 找不到就是找不到。
 */
public class SecurityDetailQueryService {

    private static final String MARKET_CODE = "CN";

    /** 未指定区间时返回的交易日数量，对应契约中的「最近 120 个交易日」。 */
    private static final int DEFAULT_KLINE_TRADING_DAYS = 120;

    private final QuoteSnapshotProvider quoteSnapshotProvider;
    private final KlineProvider klineProvider;
    private final TradingCalendarProvider tradingCalendarProvider;
    private final Clock clock;

    public SecurityDetailQueryService(
            QuoteSnapshotProvider quoteSnapshotProvider,
            KlineProvider klineProvider,
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        this.quoteSnapshotProvider = quoteSnapshotProvider;
        this.klineProvider = klineProvider;
        this.tradingCalendarProvider = tradingCalendarProvider;
        this.clock = clock;
    }

    /** STK-04：个股行情快照。 */
    public QuoteSnapshot getQuote(String securityId) {
        String normalized = normalizeSecurityId(securityId);
        return quoteSnapshotProvider
                .fetch(normalized, MARKET_CODE)
                .orElseThrow(() -> new SecurityNotFoundException(normalized));
    }

    /** STK-07：日 / 周 / 月 K 线。 */
    public KlineSeries getKlines(
            String securityId,
            String period,
            String startDate,
            String endDate,
            String adjustment) {
        String normalized = normalizeSecurityId(securityId);
        KlinePeriod parsedPeriod = KlinePeriod.fromCode(period)
                .orElseThrow(() -> InvalidKlineParameterException.invalid(
                        "period 仅支持 DAY、WEEK、MONTH"));
        KlineAdjustment parsedAdjustment = resolveAdjustment(adjustment);

        LocalDate end = parseDate(endDate, "endDate").orElseGet(this::latestTradeDate);
        LocalDate start = parseDate(startDate, "startDate")
                .orElseGet(() -> defaultStartDate(end));
        validateRange(parsedPeriod, start, end);

        KlineRequest request = new KlineRequest(
                normalized, MARKET_CODE, parsedPeriod, start, end, parsedAdjustment);
        return klineProvider
                .fetch(request)
                .orElseThrow(() -> new SecurityNotFoundException(normalized));
    }

    private static String normalizeSecurityId(String securityId) {
        if (securityId == null || securityId.isBlank()) {
            throw new SecurityNotFoundException(securityId);
        }
        return securityId.trim();
    }

    private static KlineAdjustment resolveAdjustment(String adjustment) {
        if (adjustment == null || adjustment.isBlank()) {
            return KlineAdjustment.NONE;
        }
        return KlineAdjustment.fromCode(adjustment)
                .orElseThrow(() -> InvalidKlineParameterException.adjustmentNotSupported(
                        "adjustment 仅支持 NONE；前复权与后复权将在后续版本提供"));
    }

    private static Optional<LocalDate> parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.trim()));
        } catch (DateTimeParseException exception) {
            throw InvalidKlineParameterException.invalid(
                    field + " 必须是 yyyy-MM-dd 格式的日期");
        }
    }

    private static void validateRange(KlinePeriod period, LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw InvalidKlineParameterException.invalid("startDate 不能晚于 endDate");
        }
        if (start.isBefore(end.minusYears(period.maxRangeYears()))) {
            throw InvalidKlineParameterException.rangeTooLarge(
                    period.code() + " 单次最多查询 " + period.maxRangeYears() + " 年");
        }
    }

    /**
     * 最近的有效交易日。
     *
     * <p>盘中查询当日、盘后与节假日回退到上一交易日。日历不可用时降级为系统日期
     * （同 {@code SimulatedSecurityMasterProvider} 的处理），由下游按交易日过滤。
     */
    private LocalDate latestTradeDate() {
        LocalDate today = LocalDate.now(clock);
        return tradingCalendarProvider
                .find(MARKET_CODE, today)
                .map(day -> day.tradingDay() ? day.tradeDate() : day.previousTradeDate())
                .orElse(today);
    }

    /** 从区间末端向前数满 {@link #DEFAULT_KLINE_TRADING_DAYS} 个交易日。 */
    private LocalDate defaultStartDate(LocalDate endDate) {
        LocalDate cursor = endDate;
        for (int counted = 1; counted < DEFAULT_KLINE_TRADING_DAYS; counted++) {
            cursor = previousTradeDate(cursor);
        }
        return cursor;
    }

    private LocalDate previousTradeDate(LocalDate date) {
        return tradingCalendarProvider
                .find(MARKET_CODE, date)
                .map(TradingCalendarDay::previousTradeDate)
                .orElse(date.minusDays(1));
    }
}
