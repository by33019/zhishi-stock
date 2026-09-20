package cn.zhishi.stock.market.domain;

import java.time.LocalDate;

/**
 * 证券与板块的关系，对应 {@code stock_security_sector} 的一行。
 *
 * <p>{@code relationType} 取 {@code PRIMARY} / {@code SECONDARY} / {@code MEMBER}，
 * 与 V3 迁移的列注释同集合：一只证券在同一个板块里最多有一条生效关系，
 * 但可以同时属于"主行业 + 所属大类 + 概念 + 地域"。
 *
 * <p>生效窗口是关系的属性而不是板块的属性：真实数据里成分会调整，
 * "某天的成分股"必须能按日期还原（SEC-06 的 {@code effectiveDate}）。
 * 因此日期过滤在 {@link SectorProvider} 这一层完成，而不是让用例层去猜。
 */
public record SectorMember(
        String securityId,
        String sectorId,
        String relationType,
        boolean isPrimary,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {

    public static final String RELATION_PRIMARY = "PRIMARY";
    public static final String RELATION_SECONDARY = "SECONDARY";
    public static final String RELATION_MEMBER = "MEMBER";

    /** 该关系在指定日期是否生效。{@code effectiveTo} 为 {@code null} 表示至今有效。 */
    public boolean effectiveOn(LocalDate date) {
        if (date == null) {
            return true;
        }
        if (effectiveFrom != null && date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }
}
