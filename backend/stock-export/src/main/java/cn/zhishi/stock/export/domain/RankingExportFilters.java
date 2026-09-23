package cn.zhishi.stock.export.domain;

/**
 * 榜单导出的筛选条件。
 *
 * <p>与 QTE-01 的查询参数**逐项对应**，但刻意不复用 {@code RankingCriteria}：
 * 那个记录带着 {@code page} / {@code size}，而导出没有分页——
 * 一个恒为 null 的 page 字段出现在导出请求里，会让"导出到底取了多少行"变得含糊。
 *
 * <p>字段一律可为空，"未传"与"传了空串"都在用例层按未传归一
 * （同 QTE-01 的解析口径：{@code toQueryString} 会丢弃空串）。
 */
public record RankingExportFilters(
        String rankingType,
        String exchangeCodes,
        String boardCodes,
        String sectorId,
        Boolean excludeSt,
        Boolean excludeSuspended) {

    public static RankingExportFilters empty() {
        return new RankingExportFilters(null, null, null, null, null, null);
    }
}
