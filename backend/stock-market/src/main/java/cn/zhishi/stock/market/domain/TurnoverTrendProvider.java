package cn.zhishi.stock.market.domain;

import java.util.Optional;

/**
 * 成交趋势数据源端口。
 *
 * <p>返回空表示**该市场没有趋势数据**，调用方据此判定市场不受支持（404）。
 * 当前模拟实现只会对不受支持的市场返回空；真实数据源接入后，
 * 「市场存在但当前无趋势数据」需要区分成独立异常（503），届时再扩展本端口语义。
 *
 * <p>参数与校验的职责边界：{@code range} 与 {@code interval} 的合法性由应用层判定，
 * 因此实现只会收到已经规范化的取值（盘中档位的 {@code interval} 非空，跨日档位为 {@code null}）。
 */
@FunctionalInterface
public interface TurnoverTrendProvider {

    Optional<TurnoverTrend> fetch(String marketCode, TurnoverRange range, String interval);
}
