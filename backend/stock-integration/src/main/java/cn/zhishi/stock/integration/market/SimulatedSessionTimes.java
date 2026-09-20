package cn.zhishi.stock.integration.market;

import cn.zhishi.stock.market.domain.TradingCalendarDay;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 模拟源共享的"交易日时刻"辅助。
 *
 * <p>收盘时刻必须从交易日历取，**不要硬编码 15:00**——硬编码会在遇到半日市或时段调整时
 * 静默出错。总览快照的 {@code dataTime} 与个股快照的 {@code dataTime} 都要回答
 * "这一天的收盘是哪一刻"，各写一遍就会出现两个答案。
 *
 * <p>返回 {@link Optional} 而不是自己兜底：兜底策略是调用方的局部决策
 * （个股快照用当日零点表示"时刻未知"，总览用当前时刻），
 * 但**推导过程**只有这一份。
 */
final class SimulatedSessionTimes {

    /** A 股行情时间固定在北京时间（与前端渲染口径一致）。 */
    static final ZoneOffset MARKET_OFFSET = ZoneOffset.ofHours(8);

    private SimulatedSessionTimes() {
    }

    /** 某交易日的收盘时刻；日历查不到该日、或该日没有时段时返回空。 */
    static Optional<OffsetDateTime> sessionEndAt(
            TradingCalendarProvider calendar, String marketCode, LocalDate tradeDate) {
        return calendar.find(marketCode, tradeDate)
                .flatMap(TradingCalendarDay::lastSessionEnd)
                .map(close -> OffsetDateTime.of(tradeDate, close, MARKET_OFFSET));
    }
}
