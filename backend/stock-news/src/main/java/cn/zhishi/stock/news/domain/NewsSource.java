package cn.zhishi.stock.news.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 授权资讯来源，字段与 {@code news_source} 对齐（去掉 provider 凭证等不可外泄的列）。
 *
 * <p>来源是**授权闸门**的载体：一条资讯能不能展示、能不能进 AI 上下文，
 * 首先取决于它的来源此刻是否可用。因此 {@link #usableOn(LocalDate)} 是本类的核心方法，
 * 采集侧与查询侧**共用**它——两处各写一遍授权判据，必然演化出"采集时拦住了、查询时放行了"
 * 这类不会报错的缺陷。
 *
 * @param rightsValidFrom 授权起始日，{@code null} 表示无起始限制
 * @param rightsValidTo   授权截止日，{@code null} 表示无截止限制
 * @param allowAiAnalysis 是否允许摘要进入 AI 上下文；本轮只落库，消费侧在 M3-06
 */
public record NewsSource(
        long sourceId,
        String sourceCode,
        String sourceName,
        NewsSourceType sourceType,
        String homepageUrl,
        AuthorizationStatus authorizationStatus,
        LocalDate rightsValidFrom,
        LocalDate rightsValidTo,
        boolean allowAiAnalysis,
        SourceStatus status,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastFailureAt,
        int version) {

    /** 授权状态，与 {@code news_source.authorization_status} 的 CHECK 约束同集合。 */
    public enum AuthorizationStatus {

        /** 授权有效。 */
        AUTHORIZED,

        /** 授权已过期。 */
        EXPIRED,

        /** 授权被暂停。 */
        SUSPENDED,

        /** 授权状态未知——**不等于**已授权。 */
        UNKNOWN;

        public static List<String> codes() {
            return Arrays.stream(values()).map(Enum::name).toList();
        }

        public static Optional<AuthorizationStatus> fromCode(String code) {
            if (code == null) {
                return Optional.empty();
            }
            String normalized = code.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(status -> status.name().equals(normalized))
                    .findFirst();
        }
    }

    /** 运行状态，与 {@code news_source.status} 的 CHECK 约束同集合。 */
    public enum SourceStatus {

        /** 正常。 */
        ACTIVE,

        /** 降级：仍在采集，但近期有失败。降级**不阻断**采集与展示。 */
        DEGRADED,

        /** 已停用：不再采集，也不展示其内容。 */
        DISABLED;

        public static List<String> codes() {
            return Arrays.stream(values()).map(Enum::name).toList();
        }

        public static Optional<SourceStatus> fromCode(String code) {
            if (code == null) {
                return Optional.empty();
            }
            String normalized = code.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(status -> status.name().equals(normalized))
                    .findFirst();
        }
    }

    /**
     * 该来源在 {@code today} 这天是否可以采集与展示。
     *
     * <p>四个条件全部满足才算可用，任一条不满足都返回 {@code false}：
     *
     * <ol>
     *   <li>授权状态为 {@code AUTHORIZED}——{@code UNKNOWN} 也算不可用（未知不等于授权）；
     *   <li>运行状态不是 {@code DISABLED}——{@code DEGRADED} 仍可用（降级只表示近期有失败）；
     *   <li>授权起始日未到未来；
     *   <li>授权截止日未过。
     * </ol>
     *
     * <p>日期是**入参**而不是内部读时钟：采集侧按批次的基准日判定、查询侧按请求时刻的日期判定，
     * 一次请求内多次判定必须用同一个日期，否则跨零点请求会出现"前半段可见、后半段不可见"。
     */
    public boolean usableOn(LocalDate today) {
        if (authorizationStatus != AuthorizationStatus.AUTHORIZED) {
            return false;
        }
        if (status == SourceStatus.DISABLED) {
            return false;
        }
        if (rightsValidFrom != null && today.isBefore(rightsValidFrom)) {
            return false;
        }
        return rightsValidTo == null || !today.isAfter(rightsValidTo);
    }
}
