package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 任务冻结上下文的类别，取值与 {@code ai_context_snapshot.context_type} 的 CHECK 约束同集合。
 *
 * <p>七类里本轮只接入 {@link #QUOTE} / {@link #SECTOR} / {@link #NEWS} 三类
 * （见 M3-06 spec §3.4.1）。枚举保留全部七类，因为它们是**表结构的取值域**；
 * 而"本轮能取到哪几类"是用例层的事实，由 {@code AiContextPreviewService} 按实际取数结果回答，
 * 不靠枚举裁剪——否则将来接入 K 线时又要改一次枚举。
 */
public enum AiContextType {

    /** 个股行情快照。 */
    QUOTE(true),

    /** K 线区间序列。本轮未接入。 */
    KLINE(true),

    /** 板块主数据与成分关系。 */
    SECTOR(true),

    /** 主营业务。数据源未就位，本轮未接入。 */
    BUSINESS(false),

    /** 资讯摘要。 */
    NEWS(false),

    /** 事件日历。本轮未接入。 */
    CALENDAR(false),

    /** 限幅规则等业务规则。本轮未接入。 */
    RULE(false);

    private final boolean marketData;

    AiContextType(boolean marketData) {
        this.marketData = marketData;
    }

    /**
     * 是否属于「行情数据」。
     *
     * <p>用途只有一个：{@code ai_report.market_data_cutoff_at}（NOT NULL）该取哪一类截止时间。
     * 单看 {@link #QUOTE} 是不够的——{@code SECTOR} 场景的目标全是板块，
     * 它的截止时间同样来自行情批次，而板块任务里**没有** {@code QUOTE} 快照；
     * 只认 QUOTE 会让这一类任务写报告时拿到 null 而整条链路失败。
     *
     * <p>这个分类必须写在枚举上：散在渲染代码里的 {@code if (type == QUOTE || type == SECTOR)}
     * 会在接入 K 线时被漏掉一处，而漏掉的后果是截止时间为 null。
     */
    public boolean marketData() {
        return marketData;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
