package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * 由交易日历推导"最近有效交易日"与"此刻所处的交易时段"。
 *
 * <p>抽成纯函数是为了让 MKT-01 的摄入、MKT-02 的查询、以及个股/整批快照三条链路
 * 共用**同一份**口径。此前它们各写一遍，总览 Provider 因此漏掉了非交易日回退：
 * 同一份快照里广度按周日算、榜单按周五算，两处各自都"合法"，
 * 没有任何测试会因此变红。口径分歧的代价就在这里——它不报错，只让数字互相矛盾。
 */
public final class TradingSessions {

    private TradingSessions() {
    }

    /**
     * 最近的有效交易日：交易日取当日，非交易日回退到上一交易日。
     *
     * <p>日历查不到该市场时返回 {@code today} 兜底——日历缺失是数据源问题，
     * 让它升级成"接口不可用"没有收益，而"今天"是此刻唯一可用的答案。
     */
    public static LocalDate latestTradeDate(
            TradingCalendarProvider calendar, String marketCode, LocalDate today) {
        Optional<TradingCalendarDay> found = calendar.find(marketCode, today);
        if (found.isEmpty()) {
            return today;
        }
        TradingCalendarDay day = found.get();
        if (day.tradingDay()) {
            return day.tradeDate();
        }
        LocalDate previous = day.previousTradeDate();
        // 日历没给出上一交易日时退回今天：宁可给出一个可能不精确的日期，
        // 也不要把 null 顺着 tradeDate 渗进响应体（下游按非空使用它）。
        return previous != null ? previous : today;
    }

    /**
     * 此刻所处的交易时段。
     *
     * <p>只有当目标日期就是"今天"且当天是交易日时才按当前时刻匹配窗口；
     * 其余情况一律 {@link TradingSession#CLOSED}——历史或未来日期的盘中状态无法由当前时刻还原，
     * 返回一个看似实时的假状态比返回 CLOSED 更有害。
     *
     * <p>收盘后没有任何窗口覆盖，因此同样落到 CLOSED；这不是特例，
     * 与 {@link TradingCalendarDay#sessionAt(LocalTime)} 的兜底语义同源。
     */
    public static TradingSession currentSession(
            TradingCalendarDay day, LocalDate today, LocalTime now) {
        if (!day.tradeDate().equals(today) || !day.tradingDay()) {
            return TradingSession.CLOSED;
        }
        return day.sessionAt(now).orElse(TradingSession.CLOSED);
    }
}
