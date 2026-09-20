package cn.zhishi.stock.news.domain;

import cn.zhishi.stock.market.domain.MarketOverview;
import java.time.OffsetDateTime;

/**
 * 公开的资讯同步状态（NEWS-03）。
 *
 * <p>契约明确"不泄露 Provider 内部错误或配置"，因此这里**只有聚合计数与时间**，
 * 没有任何错误文本、来源名或供应商信息。
 *
 * @param delaySeconds 距上次成功同步的秒数；从未成功同步时为 {@code null}
 *                     （{@code 0} 会被读成"刚刚同步过"，那是编造）
 */
public record NewsSyncStatus(
        OverallStatus overallStatus,
        OffsetDateTime lastSuccessfulSyncAt,
        Long delaySeconds,
        int availableSourceCount,
        int failedSourceCount) {

    /**
     * 聚合状态。
     *
     * <p>三态互斥且完备：没有任何可用来源、或从未成功同步过 → {@code UNAVAILABLE}；
     * 有可用来源但存在失败 → {@code DEGRADED}；否则 {@code OK}。
     */
    public enum OverallStatus {

        /** 正常。 */
        OK,

        /** 降级：仍有可用来源，但至少一个来源处于失败状态。 */
        DEGRADED,

        /** 不可用：没有可用来源，或从未成功同步过。 */
        UNAVAILABLE
    }

    /**
     * 对外数据状态（契约 §3.6 的 {@code dataStatus}）。
     *
     * <p>这是**投影**，不是另算一遍：{@code OK → REALTIME}、{@code DEGRADED → DELAYED}、
     * {@code UNAVAILABLE → UNAVAILABLE}。
     *
     * <p>刻意**不产出 {@code STALE}**：资讯还没有定义"多久算陈旧"的阈值。契约 §3.6 的
     * "60 秒"只约束交易时段的核心行情，资讯是采集而非推送，套用它会把一次正常的采集间隔
     * 标成陈旧；随便编一个（比如 10 分钟）同样是把规定当成事实。真实源接入、采集周期
     * 确定之后再补这个阈值——在那之前，{@code STALE} 在资讯域是**不存在的取值**，
     * 而不是"暂时用不到"。
     *
     * <p>枚举住在 {@link MarketOverview.DataStatus} 是 M2 的历史位置（契约 §3.6 只定义了一份
     * 取值表）。资讯域引用它而不是自建一份，是为了让前端只认一套取值。
     */
    public MarketOverview.DataStatus dataStatus() {
        return switch (overallStatus) {
            case OK -> MarketOverview.DataStatus.REALTIME;
            case DEGRADED -> MarketOverview.DataStatus.DELAYED;
            case UNAVAILABLE -> MarketOverview.DataStatus.UNAVAILABLE;
        };
    }
}
