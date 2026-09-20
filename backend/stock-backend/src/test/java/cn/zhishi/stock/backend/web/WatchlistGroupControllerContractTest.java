package cn.zhishi.stock.backend.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyRecord;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import cn.zhishi.stock.system.watchlist.CreatedGroup;
import cn.zhishi.stock.system.watchlist.DeleteResult;
import cn.zhishi.stock.system.watchlist.WatchlistEntry;
import cn.zhishi.stock.system.watchlist.WatchlistErrorCode;
import cn.zhishi.stock.system.watchlist.WatchlistException;
import cn.zhishi.stock.system.watchlist.WatchlistGroup;
import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
import cn.zhishi.stock.system.watchlist.WatchlistItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 自选分组接口契约（{@code RESTful-API.md} §12.1 WAT-01~WAT-05、§3.7 幂等与并发）。
 *
 * <p>用真实的 {@link IdempotencyGuard} + 内存 store，而不是 mock：
 * WAT-02 的"重复提交只创建一次"是接口行为的一部分，mock 掉守卫就等于把这条契约测掉了。
 */
class WatchlistGroupControllerContractTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long GROUP_ID = 7_000_000_000_001L;
  private static final long OTHER_GROUP_ID = 7_000_000_000_002L;
  private static final long ITEM_ID = 8_000_000_000_001L;
  private static final String GROUP_ID_TEXT = "7000000000001";
  private static final String OTHER_GROUP_ID_TEXT = "7000000000002";

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-20T06:30:00Z"), ZoneId.of("Asia/Shanghai"));

  private final WatchlistGroupService groups = mock(WatchlistGroupService.class);
  private final WatchlistItemService items = mock(WatchlistItemService.class);
  private final Map<String, IdempotencyRecord> records = new HashMap<>();
  private final IdempotencyGuard guard = new IdempotencyGuard(
      new IdempotencyStore() {
        @Override
        public Optional<IdempotencyRecord> find(String scope, long userId, String key) {
          return Optional.ofNullable(records.get(scope + ":" + userId + ":" + key));
        }

        @Override
        public void save(String scope, long userId, String key, IdempotencyRecord record) {
          records.put(scope + ":" + userId + ":" + key, record);
        }
      },
      new ObjectMapper().findAndRegisterModules());

  // ---------- WAT-01 ----------

  @Test
  void wat01ReturnsTheContractFieldSetWithStringIds() throws Exception {
    when(groups.list(USER_ID))
        .thenReturn(List.of(new WatchlistGroup(GROUP_ID, USER_ID, "默认分组", 0, true, 3, 2)));

    mvc()
        .perform(get("/api/v1/watchlist-groups").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data[0].groupName").value("默认分组"))
        .andExpect(jsonPath("$.data[0].sortNo").value(0))
        .andExpect(jsonPath("$.data[0].isDefault").value(true))
        .andExpect(jsonPath("$.data[0].itemCount").value(2))
        .andExpect(jsonPath("$.data[0].version").value(3))
        .andExpect(jsonPath("$.data[0].createdAt").doesNotExist());
  }

  @Test
  void wat01OmitsTheItemsFieldUnlessItIsAskedFor() throws Exception {
    when(groups.list(USER_ID))
        .thenReturn(List.of(new WatchlistGroup(GROUP_ID, USER_ID, "默认分组", 0, true, 3, 2)));

    mvc()
        .perform(get("/api/v1/watchlist-groups").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].items").doesNotExist());

    verify(items, never()).entriesOf(anyLong(), anyLong(), anyBoolean());
  }

  /**
   * 已知问题 #15 的关闭点：M3-01 时 {@code includeItems=true} 返回 400，
   * 因为那时 {@code items: []} 只会告诉前端"这个分组里没有股票"。
   * WAT-06 定义出自选项与 {@code security} 投影之后，这个字段终于有真实来源。
   */
  @Test
  void wat01ReturnsTheRealItemsWhenIncludeItemsIsTrue() throws Exception {
    when(groups.list(USER_ID))
        .thenReturn(List.of(new WatchlistGroup(GROUP_ID, USER_ID, "默认分组", 0, true, 3, 2)));
    when(items.entriesOf(USER_ID, GROUP_ID, false)).thenReturn(List.of(entry()));

    mvc()
        .perform(get("/api/v1/watchlist-groups")
            .param("includeItems", "true")
            .principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].itemCount").value(2))
        .andExpect(jsonPath("$.data[0].items[0].itemId").value("8000000000001"))
        .andExpect(jsonPath("$.data[0].items[0].groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data[0].items[0].security.securityCode").value("600519"))
        .andExpect(jsonPath("$.data[0].items[0].quote").value(nullValue()))
        .andExpect(jsonPath("$.data[0].items[0].latestNewsCount").value(nullValue()));
  }

  // ---------- WAT-02 ----------

  @Test
  void wat02CreatesOnceAndReplaysTheSameKey() throws Exception {
    when(groups.create(USER_ID, "我的自选"))
        .thenReturn(new CreatedGroup(
            new WatchlistGroup(GROUP_ID, USER_ID, "我的自选", 1, false, 0, 0),
            OffsetDateTime.now(CLOCK)));

    mvc()
        .perform(createRequest("key-1", "我的自选"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data.isDefault").value(false))
        .andExpect(jsonPath("$.data.version").value(0))
        .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.data.itemCount").doesNotExist());

    mvc()
        .perform(createRequest("key-1", "我的自选"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.groupId").value(GROUP_ID_TEXT));

    verify(groups, times(1)).create(USER_ID, "我的自选");
  }

  @Test
  void wat02RejectsAReusedKeyWithADifferentBody() throws Exception {
    when(groups.create(USER_ID, "我的自选"))
        .thenReturn(new CreatedGroup(
            new WatchlistGroup(GROUP_ID, USER_ID, "我的自选", 1, false, 0, 0),
            OffsetDateTime.now(CLOCK)));
    mvc().perform(createRequest("key-1", "我的自选")).andExpect(status().isOk());

    mvc()
        .perform(createRequest("key-1", "另一个名字"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void wat02RequiresAnIdempotencyKey() throws Exception {
    mvc()
        .perform(post("/api/v1/watchlist-groups")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"groupName\":\"我的自选\"}")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(groups, never()).create(anyLong(), anyString());
  }

  @Test
  void wat02ReportsAnInvalidNameAsAFieldError() throws Exception {
    when(groups.create(USER_ID, "   "))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.GROUP_NAME_INVALID, "分组名称去除首尾空白后需为 1 至 20 个字符"));

    mvc()
        .perform(createRequest("key-2", "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.data.fieldErrors.groupName").isNotEmpty());
  }

  @Test
  void wat02MapsADuplicateNameTo409() throws Exception {
    when(groups.create(anyLong(), anyString()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.GROUP_NAME_EXISTS, "分组名称已存在"));

    mvc()
        .perform(createRequest("key-3", "我的自选"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WATCHLIST_GROUP_NAME_EXISTS"));
  }

  // ---------- WAT-03 ----------

  @Test
  void wat03AcceptsBothQuotedAndBareIfMatchAndReturnsTheNewVersion() throws Exception {
    when(groups.rename(USER_ID, GROUP_ID, "核心持仓", 3))
        .thenReturn(new WatchlistGroup(GROUP_ID, USER_ID, "核心持仓", 1, false, 4, 0));

    for (String ifMatch : new String[] {"3", "\"3\""}) {
      mvc()
          .perform(renameRequest(ifMatch))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.groupName").value("核心持仓"))
          .andExpect(jsonPath("$.data.version").value(4));
    }
  }

  @Test
  void wat03RejectsAMissingOrUnusableIfMatch() throws Exception {
    for (String ifMatch : new String[] {null, "", "  ", "*", "abc"}) {
      MockHttpServletRequestBuilder builder = patch("/api/v1/watchlist-groups/" + GROUP_ID_TEXT)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"groupName\":\"核心持仓\"}")
          .principal(authentication());
      if (ifMatch != null) {
        builder = builder.header("If-Match", ifMatch);
      }
      mvc()
          .perform(builder)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    verify(groups, never()).rename(anyLong(), anyLong(), anyString(), anyInt());
  }

  @Test
  void wat03MapsAVersionConflictTo409() throws Exception {
    when(groups.rename(anyLong(), anyLong(), anyString(), anyInt()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.VERSION_CONFLICT, "分组已被其他会话修改，请刷新后重试"));

    mvc()
        .perform(renameRequest("1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WATCHLIST_VERSION_CONFLICT"));
  }

  @Test
  void wat03TreatsAnotherUsersGroupAsNotFound() throws Exception {
    when(groups.rename(anyLong(), anyLong(), anyString(), anyInt()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.RESOURCE_NOT_FOUND, "分组不存在"));

    mvc()
        .perform(renameRequest("0"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WATCHLIST_RESOURCE_NOT_FOUND"));
  }

  // ---------- WAT-04 ----------

  @Test
  void wat04ReturnsDeletedAndMovedItemCount() throws Exception {
    when(groups.delete(USER_ID, GROUP_ID, 2, OTHER_GROUP_ID)).thenReturn(new DeleteResult(true, 4));

    mvc()
        .perform(delete("/api/v1/watchlist-groups/" + GROUP_ID_TEXT)
            .header("If-Match", "2")
            .param("moveItemsToGroupId", OTHER_GROUP_ID_TEXT)
            .principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true))
        .andExpect(jsonPath("$.data.movedItemCount").value(4));
  }

  @Test
  void wat04MapsTheDefaultGroupRefusalTo409() throws Exception {
    when(groups.delete(anyLong(), anyLong(), anyInt(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.DEFAULT_GROUP_CANNOT_DELETE, "默认分组不可删除"));

    mvc()
        .perform(delete("/api/v1/watchlist-groups/" + GROUP_ID_TEXT)
            .header("If-Match", "0")
            .principal(authentication()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DEFAULT_GROUP_CANNOT_DELETE"));
  }

  @Test
  void wat04MapsAMissingTargetGroupTo400() throws Exception {
    when(groups.delete(anyLong(), anyLong(), anyInt(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.TARGET_GROUP_REQUIRED, "分组内仍有自选项，删除前必须用 moveItemsToGroupId 指定接收分组"));

    mvc()
        .perform(delete("/api/v1/watchlist-groups/" + GROUP_ID_TEXT)
            .header("If-Match", "0")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WATCHLIST_TARGET_GROUP_REQUIRED"));
  }

  // ---------- WAT-05 ----------

  @Test
  void wat05AcceptsStringIdsAndReturnsTheNewOrder() throws Exception {
    when(groups.reorder(USER_ID, List.of(OTHER_GROUP_ID, GROUP_ID)))
        .thenReturn(List.of(
            new WatchlistGroup(OTHER_GROUP_ID, USER_ID, "B", 0, false, 1, 0),
            new WatchlistGroup(GROUP_ID, USER_ID, "A", 1, true, 1, 0)));

    mvc()
        .perform(reorderRequest("[\"" + OTHER_GROUP_ID_TEXT + "\",\"" + GROUP_ID_TEXT + "\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].groupId").value(OTHER_GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data[0].sortNo").value(0))
        .andExpect(jsonPath("$.data[1].sortNo").value(1));
  }

  @Test
  void wat05RejectsANonNumericIdSet() throws Exception {
    mvc()
        .perform(reorderRequest("[\"abc\"]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(groups, never()).reorder(anyLong(), any());
  }

  @Test
  void wat05MapsAnIncompleteIdSetTo400() throws Exception {
    when(groups.reorder(anyLong(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.INVALID_REQUEST, "groupIds 必须恰好包含本人全部有效分组且不重复"));

    mvc()
        .perform(reorderRequest("[\"" + GROUP_ID_TEXT + "\"]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void aNonNumericGroupIdInThePathIsRejectedAsABadRequest() throws Exception {
    mvc()
        .perform(delete("/api/v1/watchlist-groups/abc")
            .header("If-Match", "0")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(
            new WatchlistGroupController(groups, items, guard, CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static WatchlistEntry entry() {
    return new WatchlistEntry(
        ITEM_ID, GROUP_ID, 600_519L, 0, 0, OffsetDateTime.now(CLOCK),
        new SecuritySummary(
            "sim-600519", "SH.600519", "600519", "模拟证券600519", "SH", "STOCK", "MAIN",
            "LISTED", false, false, 2, null, null),
        null,
        null);
  }

  private MockHttpServletRequestBuilder createRequest(String key, String groupName) {
    return post("/api/v1/watchlist-groups")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"groupName\":\"" + groupName + "\"}")
        .principal(authentication());
  }

  private MockHttpServletRequestBuilder renameRequest(String ifMatch) {
    return patch("/api/v1/watchlist-groups/" + GROUP_ID_TEXT)
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"groupName\":\"核心持仓\"}")
        .principal(authentication());
  }

  private MockHttpServletRequestBuilder reorderRequest(String groupIdsJson) {
    return put("/api/v1/watchlist-groups/order")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"groupIds\":" + groupIdsJson + "}")
        .principal(authentication());
  }

  private static UsernamePasswordAuthenticationToken authentication() {
    var principal = new AccessTokenPrincipal(
        USER_ID,
        "demo",
        Set.of("watchlist:read"),
        "jti-1",
        Instant.parse("2026-09-20T07:00:00Z"));
    return new UsernamePasswordAuthenticationToken(principal, "token", Set.of());
  }
}
