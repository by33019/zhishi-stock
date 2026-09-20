package cn.zhishi.stock.news.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 资讯相关时间参数的解析口径（NEWS-01/STK-10/SEC-07 的 {@code startAt}/{@code endAt}
 * 与 WAT-11 的 {@code newsSince} 共用）。
 *
 * <p>接受两种写法：
 *
 * <ul>
 *   <li>完整 ISO-8601 偏移时间（{@code 2026-09-18T10:00:00+08:00}）；
 *   <li>纯日期（{@code 2026-09-18}）——作为区间端点时取当日的起点或终点，
 *       否则 {@code endAt=2026-09-18} 会把当天 10:00 发布的资讯排除在外。
 * </ul>
 *
 * <p>放在 domain 而不是某个用例里：同一串 {@code newsSince} 在资讯中心与自选页
 * 必须被解析成**同一个时刻**。两处各写一份解析不会报错，只会让"筛选没生效"
 * 变得无法解释——而这正是最容易漏掉的一类缺陷。
 *
 * <p>失败抛 {@link IllegalArgumentException}，由调用方翻成各自模块的错误码：
 * 解析口径只有一份，异常归属仍留在各自域内（{@code stock-system} 因此不必依赖
 * {@code stock-news} 的用例层）。
 */
public final class NewsTimestamps {

    /** 纯日期写法恰好 10 个字符（{@code yyyy-MM-dd}）。 */
    private static final int DATE_ONLY_LENGTH = 10;

    private NewsTimestamps() {
    }

    /**
     * @param field    出错时写进消息的字段名（如 {@code startAt} / {@code newsSince}）
     * @param endOfDay 纯日期时是否取当日**终点**（区间右端点传 {@code true}）
     * @param zone     纯日期换算成时刻时使用的时区（与调用方的 {@code Clock} 同源）
     * @return 空值或空白原样返回 {@code null}（语义为"不筛选"）
     */
    public static OffsetDateTime parse(String value, String field, boolean endOfDay, ZoneId zone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == DATE_ONLY_LENGTH) {
                LocalDate date = LocalDate.parse(text);
                return (endOfDay ? date.plusDays(1).atStartOfDay() : date.atStartOfDay())
                        .atZone(zone)
                        .toOffsetDateTime();
            }
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    field + " 必须是 ISO-8601 时间（如 2026-09-18T10:00:00+08:00）或日期（如 2026-09-18）");
        }
    }
}
