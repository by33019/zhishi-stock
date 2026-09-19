package cn.zhishi.stock.market.application;

/**
 * 证券列表查询条件。
 *
 * <p>字段顺序与 {@code RESTful-API.md} STK-02 的 Query 参数顺序一致。
 * 所有字段都可为空，空表示该条件不参与筛选。
 *
 * <p>刻意不在这里做校验：校验集中在 {@link SecurityQueryService}，
 * 便于用单测覆盖全部分支，也让 Controller 只承担参数搬运。
 */
public record SecurityListCriteria(
    String keyword,
    String securityType,
    String exchangeCode,
    String boardCode,
    String listingStatus,
    String sectorId,
    Integer page,
    Integer size,
    String sort) {

  public static SecurityListCriteria empty() {
    return new SecurityListCriteria(null, null, null, null, null, null, null, null, null);
  }
}
