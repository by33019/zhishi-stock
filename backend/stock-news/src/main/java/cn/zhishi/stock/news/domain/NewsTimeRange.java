package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;

/**
 * 资讯的可选时间范围。
 *
 * <p>两个字段都可空：库里没有任何资讯时范围不存在，用"今天"或"一年前"填充
 * 会让前端的日期选择器给出一个**看起来合法但没有任何数据**的范围。
 */
public record NewsTimeRange(OffsetDateTime startAt, OffsetDateTime endAt) {

    public static NewsTimeRange empty() {
        return new NewsTimeRange(null, null);
    }

    /**
     * 命名刻意避开 {@code isXxx}：无参 {@code isXxx()} 会被 Jackson 按 getter 规则
     * 序列化进响应（已知问题 #21——响应里多出契约外的 {@code empty} 字段）。
     * {@code blank} 不是 getter 形态，Jackson 不认识它。
     */
    public boolean blank() {
        return startAt == null && endAt == null;
    }
}
