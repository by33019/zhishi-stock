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
    QUOTE,

    /** K 线区间序列。本轮未接入。 */
    KLINE,

    /** 板块主数据与成分关系。 */
    SECTOR,

    /** 主营业务。数据源未就位，本轮未接入。 */
    BUSINESS,

    /** 资讯摘要。 */
    NEWS,

    /** 事件日历。本轮未接入。 */
    CALENDAR,

    /** 限幅规则等业务规则。本轮未接入。 */
    RULE;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
