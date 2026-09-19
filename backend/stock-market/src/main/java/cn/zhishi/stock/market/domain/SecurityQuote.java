package cn.zhishi.stock.market.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 单只证券的行情快照，是市场广度计数的输入单位。 */
public record SecurityQuote(
        String securityId,
        String securityCode,
        String exchangeCode,
        String securityName,
        String boardCode,
        String securityType,
        boolean st,
        boolean suspended,
        LocalDate listedDate,
        BigDecimal previousClosePrice,
        BigDecimal latestPrice) {

    /** 仅替换最新价，其余字段原样保留。 */
    public SecurityQuote withLatestPrice(BigDecimal price) {
        return new SecurityQuote(
                securityId,
                securityCode,
                exchangeCode,
                securityName,
                boardCode,
                securityType,
                st,
                suspended,
                listedDate,
                previousClosePrice,
                price);
    }

    /** 仅替换停牌标记，其余字段原样保留。 */
    public SecurityQuote withSuspended(boolean halted) {
        return new SecurityQuote(
                securityId,
                securityCode,
                exchangeCode,
                securityName,
                boardCode,
                securityType,
                st,
                halted,
                listedDate,
                previousClosePrice,
                latestPrice);
    }
}
