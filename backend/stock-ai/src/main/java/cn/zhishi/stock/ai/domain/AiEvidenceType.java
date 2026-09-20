package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 报告证据类型，取值与 {@code ai_evidence.evidence_type} 的 CHECK 约束同集合。
 *
 * <p>比 {@link AiContextType} 多一个 {@link #ANNOUNCEMENT}：公告在上下文里归入
 * {@code NEWS}（都是资讯域取数），但在证据侧要能区分"这是公司公告"还是"这是媒体报道"——
 * 两者的可信度与解读方式不同，混在一起会让用户无法判断依据的性质。
 */
public enum AiEvidenceType {

    /** 行情快照。 */
    QUOTE,

    /** K 线序列。本轮未接入。 */
    KLINE,

    /** 新闻。 */
    NEWS,

    /** 公告。 */
    ANNOUNCEMENT,

    /** 主营业务。本轮未接入。 */
    BUSINESS,

    /** 板块数据。 */
    SECTOR,

    /** 业务规则。本轮未接入。 */
    RULE;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
