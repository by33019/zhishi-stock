package cn.zhishi.stock.market.application;

/**
 * 板块主数据查询条件，字段顺序与 {@code RESTful-API.md} §10 SEC-01 的 Query 参数一致。
 *
 * <p>刻意不在这里做校验：校验集中在 {@link SectorQueryService}，便于用单测覆盖全部分支，
 * 也让 Controller 只承担参数搬运（同 {@link RankingCriteria}）。
 */
public record SectorCriteria(
        String sectorType,
        String parentId,
        String keyword,
        String status) {

    public static SectorCriteria empty() {
        return new SectorCriteria(null, null, null, null);
    }
}
