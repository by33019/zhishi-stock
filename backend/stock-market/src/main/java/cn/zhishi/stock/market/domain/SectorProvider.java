package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 板块主数据与成分关系的读取端口。
 *
 * <p>真实数据源接入时由 SQL 实现替换（{@code stock_sector} + {@code stock_security_sector}），
 * 用例层不变。
 *
 * <h2>为什么关系一次全取，而不是"每个板块查一次"</h2>
 * SEC-02 板块排行要对 39 个板块各取一次成分。若端口是 {@code findMembers(sectorId)}，
 * 一次排行就是 39 次全表关系计算；而 QTE-01 的 {@code sectorId} 筛选又要再来一遍。
 * 因此端口提供"按板块分组的全量关系"，取数一次，索引建一次。
 */
public interface SectorProvider {

    /** 全部板块主数据（含停用板块——是否停用由调用方按规则判断）。 */
    List<Sector> findAll(String marketCode);

    /**
     * 按 {@code sectorId} 分组的成分关系。
     *
     * @param effectiveDate 生效日期；为 {@code null} 表示「按当前有效关系解析」，
     *                      由实现决定基准日。用例层因此不必为了取一个日期而额外依赖交易日历
     * @return 键为 {@code sectorId}；没有任何关系的板块可以不出现在返回值里
     */
    Map<String, List<SectorMember>> memberships(String marketCode, LocalDate effectiveDate);
}
