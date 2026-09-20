package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.watchlist.CreatedGroup;
import cn.zhishi.stock.system.watchlist.DeleteResult;
import cn.zhishi.stock.system.watchlist.WatchlistErrorCode;
import cn.zhishi.stock.system.watchlist.WatchlistException;
import cn.zhishi.stock.system.watchlist.WatchlistGroup;
import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
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
 * 自选分组（契约 §12.1 WAT-01~WAT-05）。
 *
 * <p>路径统一在 {@code /api/v1/watchlist-groups} 下，全部要求登录——
 * {@code SecurityConfiguration} 里没有为这个前缀开 {@code permitAll}，
 * 因此落到 {@code anyRequest().authenticated()}；{@code SecurityConfigurationTest}
 * 有一条断言把这个事实钉住，防止将来有人加宽公共前缀时顺手放开写接口。
 */
@RestController
@RequestMapping("/api/v1/watchlist-groups")
public class WatchlistGroupController {

    /** 幂等范围（契约 §3.7 的"业务范围"）：同一用户在同一范围内键唯一。 */
    private static final String CREATE_SCOPE = "watchlist-group:create";

    private final WatchlistGroupService groups;
    private final IdempotencyGuard idempotency;
    private final Clock clock;

    public WatchlistGroupController(
            WatchlistGroupService groups, IdempotencyGuard idempotency, Clock clock) {
        this.groups = groups;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<List<GroupView>> list(
            Authentication authentication,
            @RequestParam(value = "includeItems", required = false, defaultValue = "false")
                    boolean includeItems,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        if (includeItems) {
            // 契约把 items 标为可选，但本轮只实现 includeItems=false。
            // 返回 items: [] 会告诉前端"这个分组里没有股票"——分组里其实有的话那就是编造数据，
            // 与 M2-08「没有数据来源的字段降级为尚未实现」是同一条原则：宁可响亮失败。
            throw new WatchlistException(
                    WatchlistErrorCode.INVALID_REQUEST,
                    "includeItems=true 尚未实现：分组内自选项由 WAT-06 提供（M3-02）");
        }
        return success(groups.list(userId).stream().map(GroupView::from).toList(), request);
    }

    @PostMapping
    public ApiResponse<CreatedGroupView> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateGroupRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        CreatedGroupView created = idempotency.execute(
                CREATE_SCOPE,
                userId,
                IdempotencyGuard.requireKey(idempotencyKey),
                body,
                CreatedGroupView.class,
                () -> CreatedGroupView.from(groups.create(userId, body.groupName())));
        return success(created, request);
    }

    @PatchMapping("/{groupId}")
    public ApiResponse<GroupView> rename(
            Authentication authentication,
            @PathVariable long groupId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody RenameGroupRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        WatchlistGroup renamed =
                groups.rename(userId, groupId, body.groupName(), IfMatch.version(ifMatch));
        return success(GroupView.from(renamed), request);
    }

    @DeleteMapping("/{groupId}")
    public ApiResponse<DeleteView> delete(
            Authentication authentication,
            @PathVariable long groupId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(value = "moveItemsToGroupId", required = false) Long moveItemsToGroupId,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        DeleteResult result =
                groups.delete(userId, groupId, IfMatch.version(ifMatch), moveItemsToGroupId);
        return success(new DeleteView(result.deleted(), result.movedItemCount()), request);
    }

    @PutMapping("/order")
    public ApiResponse<List<GroupView>> reorder(
            Authentication authentication,
            @RequestBody ReorderGroupsRequest body,
            HttpServletRequest request) {
        long userId = principal(authentication).userId();
        return success(
                groups.reorder(userId, body.groupIds()).stream().map(GroupView::from).toList(),
                request);
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(data, TraceIdFilter.current(request), OffsetDateTime.now(clock));
    }

    /**
     * WAT-01 / WAT-03 / WAT-05 的分组视图。
     *
     * <p>字段集与契约的"返回参数"逐字一致：WAT-01 不列 {@code createdAt}，
     * 因此这里没有它（新建分组的视图另见 {@link CreatedGroupView}）。
     * {@code groupId} 用字符串：Snowflake 主键超出 JS 安全整数范围。
     */
    public record GroupView(
            String groupId,
            String groupName,
            int sortNo,
            boolean isDefault,
            int itemCount,
            int version) {

        static GroupView from(WatchlistGroup group) {
            return new GroupView(
                    Long.toString(group.groupId()),
                    group.groupName(),
                    group.sortNo(),
                    group.isDefault(),
                    group.itemCount(),
                    group.version());
        }
    }

    /** WAT-02 的返回：契约在这里列了 {@code createdAt}、没有 {@code itemCount}。 */
    public record CreatedGroupView(
            String groupId,
            String groupName,
            int sortNo,
            boolean isDefault,
            int version,
            OffsetDateTime createdAt) {

        static CreatedGroupView from(CreatedGroup created) {
            WatchlistGroup group = created.group();
            return new CreatedGroupView(
                    Long.toString(group.groupId()),
                    group.groupName(),
                    group.sortNo(),
                    group.isDefault(),
                    group.version(),
                    created.createdAt());
        }
    }

    public record DeleteView(boolean deleted, int movedItemCount) {
    }

    public record CreateGroupRequest(String groupName) {
    }

    public record RenameGroupRequest(String groupName) {
    }

    /**
     * WAT-05 的请求体。
     *
     * <p>{@code List<Long>} 而不是 {@code List<String>}：Jackson 会把 {@code "7001"} 强制成
     * {@code 7001}，非数字则抛 {@code HttpMessageNotReadableException} → 400，
     * 正好是契约要的"拒绝"。
     */
    public record ReorderGroupsRequest(List<Long> groupIds) {
    }
}
