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

    public boolean isEmpty() {
        return startAt == null && endAt == null;
    }
}
