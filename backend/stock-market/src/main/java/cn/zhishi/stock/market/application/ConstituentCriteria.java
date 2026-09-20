package cn.zhishi.stock.market.application;

/**
 * 板块成分股查询条件，字段顺序与 {@code RESTful-API.md} §10 SEC-06 的 Query 参数一致。
 *
 * <p>{@code effectiveDate} 用 {@code String}：日期格式非法要返回业务码
 * {@code INVALID_REQUEST} 而不是 {@code MethodArgumentTypeMismatchException}
 * （同 {@code InvalidKlineParameterException} 对 {@code startDate} 的处理）。
 * 为 {@code null} 表示"按当前有效关系解析"。
 */
public record ConstituentCriteria(
        String effectiveDate,
        String rankingType,
        Integer page,
        Integer size) {

    public static ConstituentCriteria empty() {
        return new ConstituentCriteria(null, null, null, null);
    }
}
