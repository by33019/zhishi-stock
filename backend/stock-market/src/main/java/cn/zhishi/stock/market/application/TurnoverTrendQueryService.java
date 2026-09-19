package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.TurnoverRange;
import cn.zhishi.stock.market.domain.TurnoverTrend;
import cn.zhishi.stock.market.domain.TurnoverTrendProvider;
import java.util.Locale;
import java.util.Set;

/**
 * MKT-04 用例：返回市场成交量额趋势。
 *
 * <p>参数校验集中在这里，Provider 只负责取数：
 * <ul>
 *   <li>{@code range} 必填，取值 {@code TODAY} / {@code 5D} / {@code 20D}；</li>
 *   <li>盘中档位 {@code interval} 可选，缺省 {@code 1m}，取值 {@code 1m|5m|15m|30m|60m}；</li>
 *   <li>跨日档位不接受 {@code interval}——与其静默忽略一个客户端以为生效的参数，
 *       不如明确报错（与文档「不支持的参数返回明确错误而非静默替换」一致）。</li>
 * </ul>
 */
public class TurnoverTrendQueryService {

    private static final String DEFAULT_INTERVAL = "1m";

    private static final Set<String> SUPPORTED_INTERVALS =
            Set.of("1m", "5m", "15m", "30m", "60m");

    private final TurnoverTrendProvider provider;

    public TurnoverTrendQueryService(TurnoverTrendProvider provider) {
        this.provider = provider;
    }

    public TurnoverTrend getTrend(String marketCode, String range, String interval) {
        TurnoverRange parsedRange = TurnoverRange.fromCode(range)
                .orElseThrow(() -> new InvalidTurnoverParameterException(
                        "range 仅支持 TODAY、5D、20D"));
        String normalizedInterval = normalizeInterval(parsedRange, interval);
        String normalizedMarket = marketCode == null
                ? ""
                : marketCode.trim().toUpperCase(Locale.ROOT);
        return provider.fetch(normalizedMarket, parsedRange, normalizedInterval)
                .orElseThrow(() -> new MarketNotFoundException(marketCode));
    }

    private static String normalizeInterval(TurnoverRange range, String interval) {
        boolean absent = interval == null || interval.isBlank();
        if (!range.intraday()) {
            if (!absent) {
                throw new InvalidTurnoverParameterException(
                        "range=" + range.code() + " 以日维度聚合，不接受 interval 参数");
            }
            return null;
        }
        if (absent) {
            return DEFAULT_INTERVAL;
        }
        String normalized = interval.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_INTERVALS.contains(normalized)) {
            throw new InvalidTurnoverParameterException(
                    "interval 仅支持 1m、5m、15m、30m、60m");
        }
        return normalized;
    }
}
