package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.market.application.MarketStatusQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketStatus;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.watchlist.WatchlistErrorCode;
import cn.zhishi.stock.system.watchlist.WatchlistException;
import cn.zhishi.stock.system.watchlist.WatchlistItemService;
import cn.zhishi.stock.system.watchlist.WatchlistMembership;
import cn.zhishi.stock.system.watchlist.WatchlistOverview;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自选中心首屏聚合与"已自选"回显（契约 §12.2 WAT-11、WAT-12）。
 *
 * <p>与 {@link WatchlistItemController} 分开是因为路径前缀不同
 * （{@code /watchlists/*} 不属于某个分组的子资源），而 WAT-11 / WAT-12 又都不是
 * 自选项的增删改查。
 *
 * <p>**市场状态在这里注入、不在用例层**：那是行情域的用例
 * （{@link MarketStatusQueryService}），由组合根取来后拼进响应，
 * 于是 {@code stock-system} 只需要依赖 {@code stock-market} 的 domain。
 */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistOverviewController {

    /** 自选是 A 股业务，市场固定为 CN。 */
    private static final String MARKET_CODE = "CN";

    private final WatchlistItemService items;
    private final MarketStatusQueryService marketStatus;
    private final Clock clock;

    public WatchlistOverviewController(
            WatchlistItemService items, MarketStatusQueryService marketStatus, Clock clock) {
        this.items = items;
        this.marketStatus = marketStatus;
        this.clock = clock;
    }

    @GetMapping("/overview")
    public ApiResponse<OverviewView> overview(
            Authentication authentication,
            @RequestParam(value = "groupId", required = false) Long groupId,
            @RequestParam(value = "newsSince", required = false) String newsSince,
            HttpServletRequest request) {
        if (newsSince != null && !newsSince.isBlank()) {
            // 静默忽略一个过滤条件，会让调用方拿到"看起来正常"的响应，极难排查
            // （同 SecurityQueryService 对白名单外排序字段的处理）。宁可响亮失败。
            throw new WatchlistException(
                    WatchlistErrorCode.INVALID_REQUEST,
                    "newsSince 尚未实现：最新资讯数由资讯 Provider 提供（M3-04）");
        }
        long userId = principal(authentication).userId();
        WatchlistOverview overview = items.overview(userId, groupId);
        return success(
                OverviewView.from(overview, marketStatus.getStatus(MARKET_CODE, null)), request);
    }

    @GetMapping("/membership")
    public ApiResponse<Map<String, MembershipView>> membership(
            Authentication authentication,
            @RequestParam(value = "securityIds", required = false) String securityIds,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        Map<String, MembershipView> view = new LinkedHashMap<>();
        items.membership(userId, securityIds)
                .forEach((securityId, membership) ->
                        view.put(securityId, MembershipView.from(membership)));
        return success(view, request);
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    /**
     * WAT-11 的响应：分组摘要 + 自选行情数组 + 市场状态 + 批次数据状态。
     *
     * <p>{@code groups} 复用 WAT-01 的 {@link WatchlistGroupController.GroupView}
     * （不带嵌套 {@code items}），{@code items} 复用 WAT-06 的
     * {@link WatchlistItemController.ItemView}——两处各定义一份形状，
     * 字段增删时会各自演化且没有测试会红。
     *
     * <p>{@code limitations} 是契约"数据状态字段"的落点：人可读的降级说明。
     * 空列表表示没有任何降级；只要资讯域还没就位（M3-04），它就不会是空的。
     */
    public record OverviewView(
            List<WatchlistGroupController.GroupView> groups,
            List<WatchlistItemController.ItemView> items,
            MarketStatus marketStatus,
            String snapshotVersion,
            MarketOverview.DataStatus dataStatus,
            OffsetDateTime dataTime,
            List<String> limitations) {

        static OverviewView from(WatchlistOverview overview, MarketStatus marketStatus) {
            return new OverviewView(
                    overview.groups().stream().map(WatchlistGroupController.GroupView::from).toList(),
                    overview.entries().stream().map(WatchlistItemController.ItemView::from).toList(),
                    marketStatus,
                    overview.snapshotVersion(),
                    overview.dataStatus(),
                    overview.dataTime(),
                    overview.limitations());
        }
    }

    public record MembershipView(String groupId, String groupName, String itemId) {

        static MembershipView from(WatchlistMembership membership) {
            return new MembershipView(
                    Long.toString(membership.groupId()),
                    membership.groupName(),
                    Long.toString(membership.itemId()));
        }
    }
}
