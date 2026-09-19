package cn.zhishi.stock.market.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 证券摘要，字段与 {@code RESTful-API.md} §4.1 逐一对齐。
 *
 * <p>{@code pinyin} / {@code pinyinAbbr} 标 {@link JsonIgnore}：它们只服务搜索匹配，
 * 不属于对外契约，因此**不出现在 JSON 里**。当前模拟数据中两者恒为 {@code null}
 * （合成名称没有可核实的拼音），但匹配能力保留，等真实主数据接入即可生效。
 *
 * <p>{@code isSt} / {@code isSuspended} 显式声明 JSON 名，不依赖 Jackson 对 {@code is} 前缀的
 * 推断——推断规则在 record 上容易与预期不一致（同 {@code MarketStatus.tradingDay} 的处理）。
 */
public record SecuritySummary(
    String securityId,
    String fullSymbol,
    String securityCode,
    String securityName,
    String exchangeCode,
    String securityType,
    String boardCode,
    String listingStatus,
    @JsonProperty("isSt") boolean isSt,
    @JsonProperty("isSuspended") boolean isSuspended,
    int priceScale,
    @JsonIgnore String pinyin,
    @JsonIgnore String pinyinAbbr) {
}
