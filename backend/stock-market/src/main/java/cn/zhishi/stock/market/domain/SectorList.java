package cn.zhishi.stock.market.domain;

import java.util.List;

/**
 * 板块主数据列表（{@code RESTful-API.md} §10 SEC-01 的响应）。
 *
 * <p>契约只写「返回板块数组」。选 {@code data.items} 而不是让 {@code data} 直接是数组：
 * 与 STK-01 的 {@code data.items} 一致，且将来要加 {@code total} 之类的字段
 * 不必改变外层类型。板块数据本身不分页（39 个），因此不带分页字段。
 */
public record SectorList(List<Sector> items) {

    public SectorList {
        items = List.copyOf(items);
    }
}
