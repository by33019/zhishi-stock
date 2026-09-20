package cn.zhishi.stock.market.domain;

/**
 * 板块内的领涨 / 领跌股（{@code RESTful-API.md} §10 SEC-02 / SEC-04）。
 *
 * <p>内嵌完整的 {@link SecuritySummary} 而不是只给一个名字：前端要能直接跳转个股页
 * （PRD §7.4 SEC-03「从板块下钻到标的」），只给名称会逼着前端再查一次，
 * 或者在跳转时把名字当 ID 用——后者正是 M2-06 修掉的首页 404 的成因。
 */
public record SectorLeaderStock(
        SecuritySummary security,
        String latestPrice,
        String changeRate) {
}
