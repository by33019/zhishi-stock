package cn.zhishi.stock.market.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * MKT-02 响应体：市场交易状态。
 *
 * <p>{@code tradingDay} 显式声明 JSON 名为 {@code isTradingDay}，与接口契约逐字一致。
 */
public record MarketStatus(
        String marketCode,
        LocalDate tradeDate,
        @JsonProperty("isTradingDay") boolean tradingDay,
        MarketSessionStatus sessionStatus,
        TradingSession currentSession,
        OffsetDateTime nextSessionAt,
        OffsetDateTime calendarSourceTime) {
}
