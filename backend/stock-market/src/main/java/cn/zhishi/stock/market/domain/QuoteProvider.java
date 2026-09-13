package cn.zhishi.stock.market.domain;

@FunctionalInterface
public interface QuoteProvider {

    MarketOverview fetch(String marketCode);
}
