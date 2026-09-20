package cn.zhishi.stock.system.watchlist;

import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecuritySummary;
import java.time.OffsetDateTime;

/**
 * 对外自选项视图（WAT-06 的分页元素，WAT-11 复用同一形状）。
 *
 * <p>为什么 WAT-11 不再定义一份：两处各写一份 item 视图，字段增删时会各自演化，
 * 而这种分叉不会有任何测试变红（M2-10 的"同一个事实只允许一处实现"）。
 *
 * <p>{@code security} 与 {@code quote} 都可能为 {@code null}：
 * 前者是"证券不在主数据里"的悬空自选，后者是"这只证券当前没有行情快照"。
 * 两者都**保留条目**（PRD：部分行情失败时保留股票并局部提示，不得自动移除），
 * 由 WAT-11 的 {@code limitations} 说明降级原因。
 *
 * <p>{@code latestNewsCount} 是**该证券已确认关联且当前可见**的资讯条数
 * （口径由资讯域给出，与资讯中心列表同源）。{@code null} 表示"不知道"：
 * 悬空自选拼不出对外标识，或资讯域查不到这只证券。填 0 会告诉前端
 * "这只股票近期没有资讯"——那是编造，而"没有资讯"与"我们不知道"是两件事。
 */
public record WatchlistEntry(
        long itemId,
        long groupId,
        long securityId,
        int sortNo,
        int version,
        OffsetDateTime createdAt,
        SecuritySummary security,
        QuoteSnapshot quote,
        Integer latestNewsCount) {
}
