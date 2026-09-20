package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.watchlist.MovedItem;
import cn.zhishi.stock.system.watchlist.WatchlistEntry;
import cn.zhishi.stock.system.watchlist.WatchlistErrorCode;
import cn.zhishi.stock.system.watchlist.WatchlistException;
import cn.zhishi.stock.system.watchlist.WatchlistItem;
import cn.zhishi.stock.system.watchlist.WatchlistItemService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自选项（契约 §12.2 WAT-06~WAT-10）。
 *
 * <p>路径挂在 {@code /api/v1/watchlist-groups/{groupId}/items} 下——自选项是分组的子资源，
 * 因此每个接口都要先用 {@code {groupId}} 校验归属（用例层做），
 * 他人分组一律 404 {@code WATCHLIST_RESOURCE_NOT_FOUND}（§12.3：不泄露存在性）。
 */
@RestController
@RequestMapping("/api/v1/watchlist-groups/{groupId}/items")
public class WatchlistItemController {

    /** 幂等范围（契约 §3.7）：与分组创建的 scope 不同，两边的键不会互相干扰。 */
    private static final String ADD_SCOPE = "watchlist-item:add";

    private final WatchlistItemService items;
    private final IdempotencyGuard idempotency;
    private final Clock clock;

    public WatchlistItemController(
            WatchlistItemService items, IdempotencyGuard idempotency, Clock clock) {
        this.items = items;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<PageData<ItemView>> list(
            Authentication authentication,
            @PathVariable long groupId,
            @RequestParam(value = "includeQuote", required = false, defaultValue = "false")
                    boolean includeQuote,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(
                toPage(items.listItems(userId, groupId, includeQuote, page, size)), request);
    }

    @PostMapping
    public ApiResponse<CreatedItemView> add(
            Authentication authentication,
            @PathVariable long groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AddItemRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        CreatedItemView created = idempotency.execute(
                ADD_SCOPE,
                userId,
                IdempotencyGuard.requireKey(idempotencyKey),
                body,
                CreatedItemView.class,
                () -> CreatedItemView.from(items.addItem(userId, groupId, body.securityId())));
        return success(created, request);
    }

    @DeleteMapping("/{itemId}")
    public ApiResponse<DeletedItemView> remove(
            Authentication authentication,
            @PathVariable long groupId,
            @PathVariable long itemId,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(new DeletedItemView(items.removeItem(userId, groupId, itemId)), request);
    }

    @PatchMapping("/{itemId}")
    public ApiResponse<MovedItemView> move(
            Authentication authentication,
            @PathVariable long groupId,
            @PathVariable long itemId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody MoveItemRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        if (body.targetGroupId() == null) {
            throw new WatchlistException(
                    WatchlistErrorCode.INVALID_REQUEST, "targetGroupId 必填");
        }
        MovedItemView moved = MovedItemView.from(
                items.moveItem(
                        userId, groupId, itemId, body.targetGroupId(), IfMatch.version(ifMatch)));
        return success(moved, request);
    }

    @PutMapping("/order")
    public ApiResponse<List<ItemOrderView>> reorder(
            Authentication authentication,
            @PathVariable long groupId,
            @RequestBody ReorderItemsRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(
                items.reorderItems(userId, groupId, body.itemIds()).stream()
                        .map(ItemOrderView::from)
                        .toList(),
                request);
    }

    private static PageData<ItemView> toPage(PageData<WatchlistEntry> page) {
        return new PageData<>(
                page.items().stream().map(ItemView::from).toList(),
                page.page(),
                page.size(),
                page.total(),
                page.totalPages(),
                page.hasNext());
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    /**
     * WAT-06 的分页元素。
     *
     * <p>{@code security} 与 {@code quote} 都可能为 {@code null}：前者是"证券不在主数据里"，
     * 后者是"这只证券当前没有行情快照"。契约要求"行情不可用时仍返回自选关系"，
     * 因此两者都保留条目，不用占位对象充数。
     */
    public record ItemView(
            String itemId,
            String groupId,
            SecuritySummary security,
            int sortNo,
            int version,
            OffsetDateTime createdAt,
            QuoteSnapshot quote,
            Integer latestNewsCount) {

        static ItemView from(WatchlistEntry entry) {
            return new ItemView(
                    Long.toString(entry.itemId()),
                    Long.toString(entry.groupId()),
                    entry.security(),
                    entry.sortNo(),
                    entry.version(),
                    entry.createdAt(),
                    entry.quote(),
                    entry.latestNewsCount());
        }
    }

    /**
     * WAT-07 的返回：契约在这里列了 {@code createdAt}、**没有** {@code quote} 与
     * {@code latestNewsCount}（与 WAT-06 的差异是契约明写的，不是遗漏）。
     */
    public record CreatedItemView(
            String itemId,
            String groupId,
            SecuritySummary security,
            int sortNo,
            int version,
            OffsetDateTime createdAt) {

        static CreatedItemView from(WatchlistEntry entry) {
            return new CreatedItemView(
                    Long.toString(entry.itemId()),
                    Long.toString(entry.groupId()),
                    entry.security(),
                    entry.sortNo(),
                    entry.version(),
                    entry.createdAt());
        }
    }

    /** WAT-09 的返回：契约另在说明里要求合并时给出 {@code merged=true}。 */
    public record MovedItemView(
            String itemId, String groupId, int sortNo, int version, boolean merged) {

        static MovedItemView from(MovedItem moved) {
            WatchlistItem item = moved.item();
            return new MovedItemView(
                    Long.toString(item.itemId()),
                    Long.toString(item.groupId()),
                    item.sortNo(),
                    item.version(),
                    moved.merged());
        }
    }

    /** WAT-10 的返回：更新后的项顺序与版本。 */
    public record ItemOrderView(String itemId, String groupId, int sortNo, int version) {

        static ItemOrderView from(WatchlistItem item) {
            return new ItemOrderView(
                    Long.toString(item.itemId()),
                    Long.toString(item.groupId()),
                    item.sortNo(),
                    item.version());
        }
    }

    public record DeletedItemView(boolean deleted) {
    }

    public record AddItemRequest(String securityId) {
    }

    /**
     * WAT-09 的请求体。
     *
     * <p>{@code Long} 而不是 {@code String}：Jackson 会把 {@code "7001"} 强制成 {@code 7001}，
     * 非数字则抛 {@code HttpMessageNotReadableException} → 400，正好是契约要的"拒绝"。
     */
    public record MoveItemRequest(Long targetGroupId) {
    }

    /** WAT-10 的请求体，{@code Long} 的取舍同上。 */
    public record ReorderItemsRequest(List<Long> itemIds) {
    }
}
