package cn.zhishi.stock.news.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 内容来源类型，与 {@code news_source.source_type} 的 CHECK 约束同集合。
 *
 * <p>NEWS-04 的"来源类型"筛选项就取自本枚举。区分交易所/公司与媒体的意义在于
 * **可信度与授权性质不同**：交易所公告是权威一手信息，公司自述是当事人陈述，
 * 媒体报道是二手转述。AI 引用时这个区别要保留（M3-06）。
 */
public enum NewsSourceType {

    /** 媒体。 */
    MEDIA,

    /** 交易所。 */
    EXCHANGE,

    /** 公司（自述公告）。 */
    COMPANY,

    /** 监管机构。 */
    REGULATOR;

    /** 全部类型代码，顺序与声明一致。NEWS-04 直接返回它，避免在前端再抄一遍枚举取值。 */
    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
