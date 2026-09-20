package cn.zhishi.stock.market.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 板块主数据，字段与 {@code RESTful-API.md} §10 SEC-01 的返回参数逐一对齐。
 *
 * <p>{@code status} 标 {@link JsonIgnore}：它在服务端是**筛选与排序规则**的输入
 * （停用板块不进当前排行、SEC-04 / SEC-06 对停用板块报 {@code SECTOR_INACTIVE}），
 * 不是展示字段，而 SEC-01 的契约只列出 6 个字段。与 {@code SecuritySummary} 的
 * {@code pinyin} / {@code pinyinAbbr} 同一处理方式：能力保留，JSON 严格。
 *
 * <p>{@code parentId} 为 {@code null} 表示一级板块；层级由 {@code levelNo} 表达（从 1 开始）。
 */
public record Sector(
        String sectorId,
        String sectorCode,
        String sectorName,
        String sectorType,
        String parentId,
        int levelNo,
        @JsonIgnore String status) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 类型是否为指定值，大小写不敏感——契约的查询参数大小写不敏感，判定必须同口径。 */
    public boolean isType(SectorType type) {
        return sectorType != null && sectorType.equalsIgnoreCase(type.code());
    }
}
