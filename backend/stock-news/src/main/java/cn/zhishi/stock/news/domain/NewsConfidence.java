package cn.zhishi.stock.news.domain;

import java.math.BigDecimal;

/**
 * 关联的置信度阈值。
 *
 * <p>放在这里而不是 {@link NewsRelationResolver} 内部，是为了让**查询层与解析层
 * 引用同一个常量**：查询层只认 {@code CONFIRMED}，而"什么算 CONFIRMED"由本类回答。
 * 两处各写一个 {@code 0.7} 必然会分叉，且不会有任何测试变红——
 * 这正是"同一个事实只允许一处实现"要防的那类缺陷。
 *
 * <p>刻意**没有**"候选下限"：规则表里最低的一条是 {@code 0.50000}，再加一个
 * {@code 0.40} 的下限只会得到一段永远不会被触发的代码。噪音过滤由规则的
 * **触发条件**承担（例如名称长度、最长匹配），而不是由一个空转的阈值承担。
 *
 * <p>这个数是**拍定的**，没有真实分布可供调参（模拟源不构成分布）。做成配置项
 * 只会掩盖"这个数还没有依据"这件事，因此刻意写死并在 spec 的已知取舍里记录。
 */
public final class NewsConfidence {

    /** 达到此值即为 {@code CONFIRMED}，可进前台列表与 AI 证据；否则为 {@code CANDIDATE}。 */
    public static final BigDecimal CONFIRMED_THRESHOLD = new BigDecimal("0.70");

    private NewsConfidence() {
    }

    /** 按阈值把置信度归类为 {@code CONFIRMED} 或 {@code CANDIDATE}（永不产出 {@code REJECTED}）。 */
    public static NewsRelationStatus classify(BigDecimal confidenceScore) {
        if (confidenceScore == null) {
            throw new IllegalArgumentException("置信度不可为空：显式关联请用 1.00000");
        }
        return confidenceScore.compareTo(CONFIRMED_THRESHOLD) >= 0
                ? NewsRelationStatus.CONFIRMED
                : NewsRelationStatus.CANDIDATE;
    }
}
