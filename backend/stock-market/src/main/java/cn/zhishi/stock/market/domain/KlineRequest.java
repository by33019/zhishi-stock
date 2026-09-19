package cn.zhishi.stock.market.domain;

import java.time.LocalDate;

/**
 * K 线查询参数。
 *
 * <p>区间与复权方式在进入端口前**已由用例层校验完毕**：Provider 只负责取数，
 * 不重复判断参数合法性，也不做任何静默降级。
 */
public record KlineRequest(
        String securityId,
        String marketCode,
        KlinePeriod period,
        LocalDate startDate,
        LocalDate endDate,
        KlineAdjustment adjustment) {
}
