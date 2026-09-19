package cn.zhishi.stock.market.application;

/**
 * 榜单查询条件，字段顺序与 {@code RESTful-API.md} §9.1 QTE-01 的 Query 参数一致。
 *
 * <p>刻意不在这里做校验：校验集中在 {@link StockRankingQueryService}，
 * 便于用单测覆盖全部分支，也让 Controller 只承担参数搬运（同 {@link SecurityListCriteria}）。
 *
 * <p>{@code excludeSt} / {@code excludeSuspended} 用包装类型而不是 {@code boolean}：
 * 必须能区分"未传"与"传了 false"。两者的默认值不同（见用例层），
 * 用原始类型会把"未传"悄悄变成 {@code false}，从而让停牌证券意外进入榜单。
 */
public record RankingCriteria(
        String rankingType,
        String exchangeCodes,
        String boardCodes,
        String sectorId,
        Boolean excludeSt,
        Boolean excludeSuspended,
        Integer page,
        Integer size) {

    public static RankingCriteria empty() {
        return new RankingCriteria(null, null, null, null, null, null, null, null);
    }
}
