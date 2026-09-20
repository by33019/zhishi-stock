package cn.zhishi.stock.news.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 关联复核状态，与 {@code stock_news_relation.relation_status} 的 CHECK 约束同集合。
 *
 * <p><b>这是本里程碑最重要的一条口径</b>：契约 §11.2 明写
 * "低置信候选关联不进入普通列表和 AI 证据，只有 {@code CONFIRMED} 可用"。
 * 因此查询层唯一的关联过滤条件就是 {@code relation_status = CONFIRMED}，
 * {@code CANDIDATE} / {@code REJECTED} 只在库里可见（供后台复核）。
 *
 * <p>它同时是"AI 证据链"的门槛：M3-06 之后的 AI 上下文构建必须复用同一判据，
 * 而不是另写一份"confidence >= 0.7"——两处阈值必然分叉，且不会报错。
 */
public enum NewsRelationStatus {

    /** 已确认：可进前台列表与 AI 证据。 */
    CONFIRMED,

    /** 候选（低置信）：留档待人工复核，不进前台与 AI 证据。 */
    CANDIDATE,

    /** 已拒绝：人工或规则明确否决，留档以避免重复提出同一条候选。 */
    REJECTED;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 解析状态代码，大小写不敏感；未知取值返回空，**不回落默认值**。 */
    public static Optional<NewsRelationStatus> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(status -> status.name().equals(normalized))
                .findFirst();
    }
}
