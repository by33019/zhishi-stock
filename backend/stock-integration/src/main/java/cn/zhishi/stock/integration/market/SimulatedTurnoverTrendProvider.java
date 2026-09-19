package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TurnoverRange;
import cn.zhishi.stock.market.domain.TurnoverTrend;
import cn.zhishi.stock.market.domain.TurnoverTrendProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 确定性模拟成交趋势。
 *
 * <p>**完全确定性**：所有取值只由交易日经固定算法导出，不使用随机数、不依赖请求次数。
 * 同一天无论何时查询，其全天成交额与成交量都相同，因此「今日曲线的终点」与
 * 「该日在日维度上的那个点」必然相等——这条一致性由单测直接断言。
 *
 * <p>盘中曲线形状按 A 股实际的"开盘与尾盘放量、午间清淡"特征构造：
 * 每分钟权重 = 基准 + 开盘衰减项 + 收盘衰减项 + 确定性抖动，全程使用整数运算，
 * 刻意避开浮点——{@code Math.sin}/{@code Math.exp} 允许跨平台 1 ulp 差异，
 * 会让"确定性"这句话在 CI 与本机之间失效。
 *
 * <p>聚合口径：分钟点取**连续竞价**的两个时段（09:30–11:30、13:00–15:00），
 * 集合竞价的成交并入其后的第一个点；只返回**已经走完**的区间，
 * 不返回未完成分钟的伪数据（与 STK-06 的口径一致）。
 */
public class SimulatedTurnoverTrendProvider implements TurnoverTrendProvider {

    private static final String SUPPORTED_MARKET = "CN";

    private static final int MINUTES_PER_SESSION = 120;
    private static final int MINUTES_PER_DAY = 240;

    /** 连续竞价时段起点；集合竞价不单独建模。 */
    private static final LocalTime MORNING_START = LocalTime.of(9, 30);
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 0);

    private static final DateTimeFormatter INTRADAY_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter DAILY_TIME = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final long DAY_AMOUNT_BASE = 982_645_000_000L;
    private static final long DAY_AMOUNT_SPAN = 120_000_000_000L;
    private static final long DAY_VOLUME_BASE = 71_500_000_000L;
    private static final long DAY_VOLUME_SPAN = 9_000_000_000L;

    /** 往前找交易日时最多回溯的自然日数，避免日历长期无交易日时陷入死循环。 */
    private static final int MAX_LOOKBACK_DAYS = 400;

    private static final long[] CUMULATIVE_WEIGHTS = cumulativeWeights();

    private final Clock clock;
    private final TradingCalendarProvider calendar;

    public SimulatedTurnoverTrendProvider(Clock clock, TradingCalendarProvider calendar) {
        this.clock = clock;
        this.calendar = calendar;
    }

    @Override
    public Optional<TurnoverTrend> fetch(String marketCode, TurnoverRange range, String interval) {
        if (!SUPPORTED_MARKET.equals(marketCode)) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        return range.intraday()
                ? intraday(marketCode, interval, now)
                : daily(marketCode, range, now);
    }

    private Optional<TurnoverTrend> intraday(
            String marketCode, String interval, OffsetDateTime now) {
        int step = intervalMinutes(interval);
        LocalDate day = now.toLocalDate();
        List<LocalTime> boundaries = boundariesUpTo(marketCode, day, now.toLocalTime(), step);
        if (boundaries.isEmpty()) {
            // 今日还没有走完任何区间（盘前 / 非交易日 / 刚开盘）：回落到最近一个交易日，
            // 返回它的全天序列，保证曲线不为空。口径与 MKT-01「非交易日返回最近有效快照」一致。
            day = previousTradingDay(marketCode, day);
            if (day == null) {
                return Optional.empty();
            }
            boundaries = boundariesUpTo(marketCode, day, null, step);
        }
        if (boundaries.isEmpty()) {
            return Optional.empty();
        }

        long dayAmount = dayAmount(day);
        long dayVolume = dayVolume(day);
        long totalWeight = CUMULATIVE_WEIGHTS[MINUTES_PER_DAY];
        List<TurnoverTrend.Point> points = new ArrayList<>(boundaries.size());
        for (LocalTime boundary : boundaries) {
            long cumulativeWeight = CUMULATIVE_WEIGHTS[elapsedMinutes(boundary)];
            points.add(new TurnoverTrend.Point(
                    OffsetDateTime.of(day, boundary, now.getOffset()).format(INTRADAY_TIME),
                    Long.toString(dayAmount * cumulativeWeight / totalWeight),
                    Long.toString(dayVolume * cumulativeWeight / totalWeight)));
        }
        LocalTime cutoff = boundaries.get(boundaries.size() - 1);
        return Optional.of(new TurnoverTrend(
                marketCode,
                TurnoverRange.TODAY.code(),
                interval,
                TurnoverTrend.Unit.standard(),
                OffsetDateTime.of(day, cutoff, now.getOffset()),
                points));
    }

    private Optional<TurnoverTrend> daily(
            String marketCode, TurnoverRange range, OffsetDateTime now) {
        List<LocalDate> days = closedTradingDays(marketCode, range.tradingDayCount(), now);
        if (days.isEmpty()) {
            return Optional.empty();
        }
        LocalDate lastDay = days.get(days.size() - 1);
        Optional<LocalTime> closeTime = calendar.find(marketCode, lastDay)
                .flatMap(TradingCalendarDay::lastSessionEnd);
        if (closeTime.isEmpty()) {
            return Optional.empty();
        }
        List<TurnoverTrend.Point> points = days.stream()
                .map(day -> new TurnoverTrend.Point(
                        day.format(DAILY_TIME),
                        Long.toString(dayAmount(day)),
                        Long.toString(dayVolume(day))))
                .toList();
        return Optional.of(new TurnoverTrend(
                marketCode,
                range.code(),
                null,
                TurnoverTrend.Unit.standard(),
                OffsetDateTime.of(lastDay, closeTime.get(), now.getOffset()),
                points));
    }

    /**
     * 返回该交易日中截至 {@code upTo} 已走完的区间边界；{@code upTo} 为 {@code null} 表示取全天。
     *
     * <p>边界取"区间结束时刻"而非"区间开始时刻"：这样每个点的时间就是它累计值的生效时刻，
     * {@code dataCutoffAt} 天然等于最后一个点的时间。例如 1 分钟粒度的首个点是 09:31，
     * 表示"截至 09:31 的累计成交"。
     */
    private List<LocalTime> boundariesUpTo(
            String marketCode, LocalDate date, LocalTime upTo, int step) {
        Optional<TradingCalendarDay> calendarDay = calendar.find(marketCode, date);
        if (calendarDay.isEmpty() || !calendarDay.get().tradingDay()) {
            return List.of();
        }
        List<LocalTime> boundaries = new ArrayList<>();
        for (LocalTime sessionStart : List.of(MORNING_START, AFTERNOON_START)) {
            for (int bucket = 1; bucket <= MINUTES_PER_SESSION / step; bucket++) {
                LocalTime boundary = sessionStart.plusMinutes((long) bucket * step);
                if (upTo == null || !boundary.isAfter(upTo)) {
                    boundaries.add(boundary);
                }
            }
        }
        return boundaries;
    }

    /** 最近一个**已经收盘**的交易日；盘中与盘前都会回退到上一个交易日。 */
    private LocalDate mostRecentClosedTradingDay(String marketCode, OffsetDateTime now) {
        Optional<TradingCalendarDay> today = calendar.find(marketCode, now.toLocalDate());
        if (today.isEmpty()) {
            return null;
        }
        TradingCalendarDay day = today.get();
        boolean closed = day.tradingDay()
                && day.lastSessionEnd().map(end -> !now.toLocalTime().isBefore(end)).orElse(false);
        return closed ? day.tradeDate() : day.previousTradeDate();
    }

    private List<LocalDate> closedTradingDays(
            String marketCode, int count, OffsetDateTime now) {
        LocalDate cursor = mostRecentClosedTradingDay(marketCode, now);
        List<LocalDate> days = new ArrayList<>(count);
        for (int scanned = 0; scanned < MAX_LOOKBACK_DAYS && days.size() < count; scanned++) {
            if (cursor == null) {
                break;
            }
            days.add(cursor);
            cursor = previousTradingDay(marketCode, cursor);
        }
        Collections.reverse(days);
        return days;
    }

    private LocalDate previousTradingDay(String marketCode, LocalDate date) {
        return calendar.find(marketCode, date)
                .map(TradingCalendarDay::previousTradeDate)
                .orElse(null);
    }

    /** 该时刻在当日 240 个交易分钟中的偏移量（午休不计入）。 */
    private static int elapsedMinutes(LocalTime boundary) {
        if (!boundary.isBefore(AFTERNOON_START)) {
            return MINUTES_PER_SESSION
                    + (int) ChronoUnit.MINUTES.between(AFTERNOON_START, boundary);
        }
        return (int) ChronoUnit.MINUTES.between(MORNING_START, boundary);
    }

    private static int intervalMinutes(String interval) {
        return Integer.parseInt(interval.substring(0, interval.length() - 1));
    }

    private static long dayAmount(LocalDate date) {
        return DAY_AMOUNT_BASE + spread(date, 1001L, DAY_AMOUNT_SPAN);
    }

    private static long dayVolume(LocalDate date) {
        return DAY_VOLUME_BASE + spread(date, 2003L, DAY_VOLUME_SPAN);
    }

    private static long spread(LocalDate date, long salt, long span) {
        return Math.floorMod(mix(date.toEpochDay() + salt), 2 * span) - span;
    }

    private static long[] cumulativeWeights() {
        long[] cumulative = new long[MINUTES_PER_DAY + 1];
        long running = 0;
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            running += minuteWeight(minute);
            cumulative[minute + 1] = running;
        }
        return cumulative;
    }

    /** 单分钟权重：开盘与尾盘放量、午间清淡，叠加确定性抖动。 */
    private static int minuteWeight(int minuteIndex) {
        int fromOpen = minuteIndex;
        int fromClose = MINUTES_PER_DAY - 1 - minuteIndex;
        int openBurst = 60 / (1 + fromOpen / 5);
        int closeBurst = 60 / (1 + fromClose / 5);
        int ripple = 1 + (int) Math.floorMod(mix(minuteIndex * 7L + 13L), 12L);
        return 24 + openBurst + closeBurst + ripple;
    }

    /**
     * SplitMix64 的收尾混合：把顺序递增的输入打散成互不相关的取值。
     *
     * <p>与 {@link SimulatedSecurityQuoteProvider} 里的同名方法刻意各留一份——
     * 两个模拟器使用互不相关的取值域，不存在"应当产生同一序列"的约束，
     * 抽成公共工具反而会暗示一种并不存在的耦合。
     */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
