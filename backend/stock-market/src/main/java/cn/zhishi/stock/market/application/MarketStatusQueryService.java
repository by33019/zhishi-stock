package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.MarketStatus;
import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TradingSession;
import cn.zhishi.stock.market.domain.TradingSessions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * 市场状态查询用例（MKT-02）。
 *
 * <p>只查询"当天"时才按当前时刻推导时段；查询历史日期或未来日期时整日视为已收盘，
 * 因为那些日期的盘中状态无法由当前时刻还原，返回 CLOSED 比返回一个看似实时的假状态更诚实。
 */
public class MarketStatusQueryService {

    private final TradingCalendarProvider provider;
    private final Clock clock;

    public MarketStatusQueryService(TradingCalendarProvider provider, Clock clock) {
        this.provider = provider;
        this.clock = clock;
    }

    public MarketStatus getStatus(String marketCode, LocalDate date) {
        String normalized = normalize(marketCode);
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate target = date != null ? date : now.toLocalDate();
        TradingCalendarDay day = provider.find(normalized, target)
                .orElseThrow(() -> new MarketNotFoundException(marketCode));

        // 时段推导与 MKT-01 的摄入共用同一份规则：只有"目标日期就是今天"时才按当前时刻匹配窗口。
        TradingSession current =
                TradingSessions.currentSession(day, now.toLocalDate(), now.toLocalTime());
        boolean live = target.equals(now.toLocalDate());

        return new MarketStatus(
                normalized,
                target,
                day.tradingDay(),
                current.status(),
                current,
                live ? nextSessionAt(normalized, day, now.toLocalTime()) : null,
                day.sourceTime());
    }

    /**
     * 下一个交易时段的开始时间：先取当日尚未开始的时段（跳过盘前占位窗口）；
     * 当日已无后续时段时顺延到下一交易日；日历未给出未来交易日时返回 null。
     */
    private OffsetDateTime nextSessionAt(String marketCode, TradingCalendarDay day, LocalTime now) {
        if (day.tradingDay()) {
            Optional<LocalTime> remaining = day.windows().stream()
                    .filter(window -> window.session() != TradingSession.PRE_OPEN)
                    .map(TradingCalendarDay.Window::start)
                    .filter(start -> start.isAfter(now))
                    .findFirst();
            if (remaining.isPresent()) {
                return at(day.tradeDate(), remaining.get());
            }
        }
        LocalDate next = day.nextTradeDate();
        if (next == null) {
            return null;
        }
        return provider.find(marketCode, next)
                .flatMap(nextDay -> nextDay.firstSessionStart().map(start -> at(next, start)))
                .orElse(null);
    }

    private OffsetDateTime at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(clock.getZone()).toOffsetDateTime();
    }

    private static String normalize(String marketCode) {
        return marketCode == null ? "" : marketCode.trim().toUpperCase(Locale.ROOT);
    }
}
