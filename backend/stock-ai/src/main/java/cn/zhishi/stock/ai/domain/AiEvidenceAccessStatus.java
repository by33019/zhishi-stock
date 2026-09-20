package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 证据来源的可访问状态，取值与 {@code ai_evidence.access_status} 的 CHECK 约束同集合。
 *
 * <p>与资讯域的 {@code NewsOriginalAccessStatus}（{@code AVAILABLE} / {@code UNAVAILABLE} /
 * {@code UNKNOWN}）**不是同一回事**：那个描述"原文链接此刻能不能打开"，
 * 这个描述"这份证据在报告里被允许展示到什么程度"，多了 {@link #RESTRICTED}（授权受限）。
 * 两者的取值域不同，合并会丢信息。
 */
public enum AiEvidenceAccessStatus {

    /** 可访问。 */
    AVAILABLE,

    /** 来源已不可访问（如原文下线），但证据摘要保留。 */
    UNAVAILABLE,

    /** 授权受限：只保留摘要，不提供原文地址。 */
    RESTRICTED;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
