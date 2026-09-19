package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 交易日历的数据来源端口。
 *
 * <p>当前唯一实现是确定性模拟实现；真实日历源就位后只需替换实现类，应用层不改动。
 */
@FunctionalInterface
public interface TradingCalendarProvider {

    /** 查询指定市场、指定日期的日历；市场代码不受支持时返回空。 */
    Optional<TradingCalendarDay> find(String marketCode, LocalDate date);
}
