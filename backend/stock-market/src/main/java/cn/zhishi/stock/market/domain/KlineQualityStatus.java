package cn.zhishi.stock.market.domain;

/** K 线点的数据质量状态，见 {@code RESTful-API.md} §8.2。 */
public enum KlineQualityStatus {
    VALID,
    DELAYED,
    CORRECTED
}
