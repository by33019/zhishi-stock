package cn.zhishi.stock.market.domain;

/**
 * 板块详情头部（{@code RESTful-API.md} §10 SEC-03）：板块主数据 + 父级板块 + 当前统计。
 *
 * <p>{@code parent} 在 {@code parentId} 为 {@code null}（一级板块）或父板块不存在时为 {@code null}。
 * 父板块**查不到不等于报错**：层级关系缺失时仍应能展示该板块本身，
 * 这与 PRD §7.4 SEC-02「映射缺失时仍展示可用行情并说明成分股不完整」同源。
 *
 * <p>契约的「数据状态字段」由 {@code quote.dataStatus} 承载，不在 {@code sector} 上重复一份。
 */
public record SectorDetail(
        Sector sector,
        Sector parent,
        SectorQuote quote) {
}
