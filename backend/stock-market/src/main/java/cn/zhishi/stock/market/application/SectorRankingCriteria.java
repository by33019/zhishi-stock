package cn.zhishi.stock.market.application;

/**
 * 板块排行查询条件，字段顺序与 {@code RESTful-API.md} §10 SEC-02 的 Query 参数一致。
 *
 * <p>{@code rankingType} 用 {@code String} 而不是枚举：非法取值要返回业务码
 * {@code INVALID_REQUEST}，而枚举绑定失败会被 Spring 转成
 * {@code MethodArgumentTypeMismatchException}，把"取值不在白名单"混同为"参数格式错误"
 * （同 STK-01 / STK-02 / QTE-01 的处理）。
 */
public record SectorRankingCriteria(
        String sectorType,
        String rankingType,
        Integer page,
        Integer size) {

    public static SectorRankingCriteria empty() {
        return new SectorRankingCriteria(null, null, null, null);
    }
}
