package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TradingSession;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 确定性 A 股交易日历模拟实现。
 *
 * <p>交易日规则：周一至周五，且不在节假日集合内。
 *
 * <p>节假日刻意不硬编码在代码里——未经核实的法定节假日日期不应写死进代码库，
 * 真实日历由后续真实 Provider 提供；当前通过配置项 {@code stock.market.holidays} 注入。
 */
public class SimulatedTradingCalendarProvider implements TradingCalendarProvider {

    private static final String SUPPORTED_MARKET = "CN";

    /** 前后交易日推导的最大扫描天数，避免日历长期无交易日时陷入死循环。 */
    private static final int MAX_SCAN_DAYS = 366;

    private static final List<TradingCalendarDay.Window> ASHARE_WINDOWS = List.of(
            window(TradingSession.PRE_OPEN, 0, 0, 9, 15),
            window(TradingSession.OPENING_CALL_AUCTION, 9, 15, 9, 25),
            window(TradingSession.PRE_OPEN, 9, 25, 9, 30),
            window(TradingSession.MORNING_CONTINUOUS, 9, 30, 11, 30),
            window(TradingSession.LUNCH_BREAK, 11, 30, 13, 0),
            window(TradingSession.AFTERNOON_CONTINUOUS, 13, 0, 14, 57),
            window(TradingSession.CLOSING_CALL_AUCTION, 14, 57, 15, 0));

    private final Clock clock;
    private final Set<LocalDate> holidays;

    public SimulatedTradingCalendarProvider(Clock clock, Set<LocalDate> holidays) {
        this.clock = clock;
        this.holidays = Set.copyOf(holidays);
    }

    /** 从逗号分隔的 ISO 日期配置构造，空白项忽略。 */
    public static SimulatedTradingCalendarProvider ofCsv(Clock clock, String holidaysCsv) {
        Set<LocalDate> parsed = new LinkedHashSet<>();
        if (holidaysCsv != null) {
            Arrays.stream(holidaysCsv.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .map(LocalDate::parse)
                    .forEach(parsed::add);
        }
        return new SimulatedTradingCalendarProvider(clock, parsed);
    }

    @Override
    public Optional<TradingCalendarDay> find(String marketCode, LocalDate date) {
        if (marketCode == null || !SUPPORTED_MARKET.equals(marketCode.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        boolean trading = isTradingDay(date);
        return Optional.of(new TradingCalendarDay(
                date,
                trading,
                previousTradeDate(date),
                nextTradeDate(date),
                trading ? ASHARE_WINDOWS : List.of(),
                OffsetDateTime.now(clock)));
    }

    private boolean isTradingDay(LocalDate date) {
        return date.getDayOfWeek().getValue() <= 5 && !holidays.contains(date);
    }

    private LocalDate previousTradeDate(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        for (int scanned = 0; scanned < MAX_SCAN_DAYS; scanned++) {
            if (isTradingDay(candidate)) {
                return candidate;
            }
            candidate = candidate.minusDays(1);
        }
        return null;
    }

    private LocalDate nextTradeDate(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        for (int scanned = 0; scanned < MAX_SCAN_DAYS; scanned++) {
            if (isTradingDay(candidate)) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return null;
    }

    private static TradingCalendarDay.Window window(
            TradingSession session, int startHour, int startMinute, int endHour, int endMinute) {
        return new TradingCalendarDay.Window(
                session, LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }
}
