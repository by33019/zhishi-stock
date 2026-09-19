package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 单个日期的交易日历信息。
 *
 * <p>时段窗口为左闭右开区间（{@code start <= t < end}），覆盖当日 00:00 至最后一个时段结束。
 * 窗口之外（例如收盘之后）没有匹配项，调用方按 {@link TradingSession#CLOSED} 兜底。
 */
public record TradingCalendarDay(
        LocalDate tradeDate,
        boolean tradingDay,
        LocalDate previousTradeDate,
        LocalDate nextTradeDate,
        List<Window> windows,
        OffsetDateTime sourceTime) {

    public TradingCalendarDay {
        windows = List.copyOf(windows);
    }

    /** 返回该时刻所处的时段；不在任何窗口内时为空。 */
    public Optional<TradingSession> sessionAt(LocalTime time) {
        return windows.stream()
                .filter(window -> window.covers(time))
                .map(Window::session)
                .findFirst();
    }

    /** 当日第一个真正的交易时段开始时间；跳过盘前占位窗口，因此非交易日返回空。 */
    public Optional<LocalTime> firstSessionStart() {
        return windows.stream()
                .filter(window -> window.session() != TradingSession.PRE_OPEN)
                .map(Window::start)
                .findFirst();
    }

    /**
     * 当日最后一个交易时段的结束时间，即收盘时刻；非交易日没有时段，返回空。
     *
     * <p>与 {@link #firstSessionStart()} 对称：需要判断"当日交易是否已经结束"时用它，
     * 不要在调用方硬编码 15:00。
     */
    public Optional<LocalTime> lastSessionEnd() {
        if (windows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(windows.get(windows.size() - 1).end());
    }

    public record Window(TradingSession session, LocalTime start, LocalTime end) {

        public Window {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("时段结束时间必须晚于开始时间");
            }
        }

        public boolean covers(LocalTime time) {
            return !time.isBefore(start) && time.isBefore(end);
        }
    }
}
