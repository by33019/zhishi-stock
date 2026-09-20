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

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.SecuritySummary;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyRecord;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import cn.zhishi.stock.system.watchlist.MovedItem;
import cn.zhishi.stock.system.watchlist.WatchlistEntry;
import cn.zhishi.stock.system.watchlist.WatchlistErrorCode;
import cn.zhishi.stock.system.watchlist.WatchlistException;
import cn.zhishi.stock.system.watchlist.WatchlistItem;
import cn.zhishi.stock.system.watchlist.WatchlistItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
 * 自选项接口契约（{@code RESTful-API.md} §12.2 WAT-06~WAT-10、§3.7 幂等与并发）。
 *
 * <p>与 M3-01 的分组契约测试同样用**真实的** {@link IdempotencyGuard} + 内存 store：
 * "重复提交只创建一次"是接口行为的一部分，mock 掉守卫等于把这条契约测掉了。
 */
class WatchlistItemControllerContractTest {

  private static final long USER_ID = 9_900_000_000_003L;
  private static final long GROUP_ID = 7_000_000_000_001L;
  private static final long TARGET_GROUP_ID = 7_000_000_000_002L;
  private static final long ITEM_ID = 8_000_000_000_001L;
  private static final String GROUP_ID_TEXT = "7000000000001";
  private static final String ITEM_ID_TEXT = "8000000000001";
  private static final String SECURITY_ID = "sim-600519";

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-20T06:30:00Z"), ZoneId.of("Asia/Shanghai"));

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

  // ---------- WAT-06 ----------

  @Test
  void wat06ReturnsTheContractFieldSetWithStringIdsAndASecurityObject() throws Exception {
    when(items.listItems(USER_ID, GROUP_ID, false, null, null))
        .thenReturn(new PageData<>(List.of(entry(false)), 1, 20, 1, 1, false));

    mvc()
        .perform(get(itemsPath()).principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].itemId").value(ITEM_ID_TEXT))
        .andExpect(jsonPath("$.data.items[0].groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data.items[0].security.securityId").value(SECURITY_ID))
        .andExpect(jsonPath("$.data.items[0].security.securityCode").value("600519"))
        .andExpect(jsonPath("$.data.items[0].security.securityName").value("模拟证券600519"))
        .andExpect(jsonPath("$.data.items[0].sortNo").value(0))
        .andExpect(jsonPath("$.data.items[0].version").value(2))
        .andExpect(jsonPath("$.data.items[0].createdAt").isNotEmpty())
        .andExpect(jsonPath("$.data.items[0].quote").value(nullValue()))
        .andExpect(jsonPath("$.data.items[0].latestNewsCount").value(nullValue()));
  }

  @Test
  void wat06KeepsTheEntryWhenTheQuoteIsMissing() throws Exception {
    when(items.listItems(USER_ID, GROUP_ID, true, null, null))
        .thenReturn(new PageData<>(List.of(entry(false)), 1, 20, 1, 1, false));

    mvc()
        .perform(get(itemsPath()).param("includeQuote", "true").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].itemId").value(ITEM_ID_TEXT))
        .andExpect(jsonPath("$.data.items[0].quote").value(nullValue()));
  }

  @Test
  void wat06PassesTheQuoteFlagAndPagingThrough() throws Exception {
    when(items.listItems(USER_ID, GROUP_ID, true, 2, 5))
        .thenReturn(new PageData<>(List.of(entry(true)), 2, 5, 7, 2, false));

    mvc()
        .perform(get(itemsPath())
            .param("includeQuote", "true")
            .param("page", "2")
            .param("size", "5")
            .principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].quote.latestPrice").value("10.50"));

    verify(items).listItems(USER_ID, GROUP_ID, true, 2, 5);
  }

  @Test
  void wat06TreatsAnotherUsersGroupAsNotFound() throws Exception {
    when(items.listItems(anyLong(), anyLong(), anyBoolean(), any(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.RESOURCE_NOT_FOUND, "分组不存在"));

    mvc()
        .perform(get(itemsPath()).principal(authentication()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WATCHLIST_RESOURCE_NOT_FOUND"));
  }

  @Test
  void wat06MapsAnInvalidPageSizeTo400() throws Exception {
    when(items.listItems(anyLong(), anyLong(), anyBoolean(), any(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.INVALID_REQUEST, "size 必须为 1 至 100"));

    mvc()
        .perform(get(itemsPath()).param("size", "101").principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void aNonNumericGroupIdOrItemIdInThePathIsRejectedAsABadRequest() throws Exception {
    mvc()
        .perform(get("/api/v1/watchlist-groups/abc/items").principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    mvc()
        .perform(delete("/api/v1/watchlist-groups/" + GROUP_ID_TEXT + "/items/abc")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  // ---------- WAT-07 ----------

  @Test
  void wat07CreatesOnceAndReplaysTheSameKey() throws Exception {
    when(items.addItem(USER_ID, GROUP_ID, SECURITY_ID)).thenReturn(entry(false));

    mvc()
        .perform(addRequest("key-1", SECURITY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.itemId").value(ITEM_ID_TEXT))
        .andExpect(jsonPath("$.data.groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data.security.securityId").value(SECURITY_ID))
        .andExpect(jsonPath("$.data.sortNo").value(0))
        .andExpect(jsonPath("$.data.version").value(2))
        .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
        // 契约的 WAT-07 字段表里没有这两项
        .andExpect(jsonPath("$.data.quote").doesNotExist())
        .andExpect(jsonPath("$.data.latestNewsCount").doesNotExist());

    mvc()
        .perform(addRequest("key-1", SECURITY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.itemId").value(ITEM_ID_TEXT));

    verify(items, times(1)).addItem(USER_ID, GROUP_ID, SECURITY_ID);
  }

  @Test
  void wat07RejectsAReusedKeyWithADifferentBody() throws Exception {
    when(items.addItem(USER_ID, GROUP_ID, SECURITY_ID)).thenReturn(entry(false));
    mvc().perform(addRequest("key-1", SECURITY_ID)).andExpect(status().isOk());

    mvc()
        .perform(addRequest("key-1", "sim-000001"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void wat07RequiresAnIdempotencyKey() throws Exception {
    mvc()
        .perform(post(itemsPath())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"securityId\":\"" + SECURITY_ID + "\"}")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(items, never()).addItem(anyLong(), anyLong(), anyString());
  }

  @Test
  void wat07MapsAnUnknownSecurityTo404() throws Exception {
    when(items.addItem(anyLong(), anyLong(), anyString()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.RESOURCE_NOT_FOUND, "证券不存在"));

    mvc()
        .perform(addRequest("key-2", "sim-999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WATCHLIST_RESOURCE_NOT_FOUND"));
  }

  /** 契约 §12.3："新增时需返回状态提醒"——提醒就是返回的 security 上的状态字段。 */
  @Test
  void wat07CarriesTheListingStatusNoticeInsideTheSecurityObject() throws Exception {
    when(items.addItem(USER_ID, GROUP_ID, SECURITY_ID)).thenReturn(entry(false, summary(true)));

    mvc()
        .perform(addRequest("key-3", SECURITY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.security.listingStatus").value("SUSPENDED"))
        .andExpect(jsonPath("$.data.security.isSuspended").value(true));
  }

  // ---------- WAT-08 ----------

  @Test
  void wat08ReportsWhetherARowWasRemovedAndStaysIdempotent() throws Exception {
    when(items.removeItem(USER_ID, GROUP_ID, ITEM_ID)).thenReturn(true, false);

    mvc()
        .perform(delete(itemsPath() + "/" + ITEM_ID_TEXT).principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true));

    mvc()
        .perform(delete(itemsPath() + "/" + ITEM_ID_TEXT).principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(false));
  }

  // ---------- WAT-09 ----------

  @Test
  void wat09AcceptsBothQuotedAndBareIfMatchAndReportsWhetherItMerged() throws Exception {
    when(items.moveItem(USER_ID, GROUP_ID, ITEM_ID, TARGET_GROUP_ID, 2))
        .thenReturn(new MovedItem(item(TARGET_GROUP_ID, 5, 3), true));

    for (String ifMatch : new String[] {"2", "\"2\""}) {
      mvc()
          .perform(moveRequest(ifMatch, TARGET_GROUP_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.itemId").value(ITEM_ID_TEXT))
          .andExpect(jsonPath("$.data.groupId").value("7000000000002"))
          .andExpect(jsonPath("$.data.sortNo").value(5))
          .andExpect(jsonPath("$.data.version").value(3))
          .andExpect(jsonPath("$.data.merged").value(true));
    }
  }

  @Test
  void wat09RejectsAMissingOrUnusableIfMatch() throws Exception {
    for (String ifMatch : new String[] {null, "", "  ", "*", "abc"}) {
      MockHttpServletRequestBuilder builder = patch(itemsPath() + "/" + ITEM_ID_TEXT)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"targetGroupId\":\"" + TARGET_GROUP_ID + "\"}")
          .principal(authentication());
      if (ifMatch != null) {
        builder = builder.header("If-Match", ifMatch);
      }
      mvc()
          .perform(builder)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    verify(items, never()).moveItem(anyLong(), anyLong(), anyLong(), anyLong(), anyInt());
  }

  @Test
  void wat09MapsAVersionConflictTo409() throws Exception {
    when(items.moveItem(anyLong(), anyLong(), anyLong(), anyLong(), anyInt()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.VERSION_CONFLICT, "自选项已被其他会话修改，请刷新后重试"));

    mvc()
        .perform(moveRequest("1", TARGET_GROUP_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WATCHLIST_VERSION_CONFLICT"));
  }

  @Test
  void wat09RejectsAMissingOrNonNumericTargetGroupId() throws Exception {
    mvc()
        .perform(patch(itemsPath() + "/" + ITEM_ID_TEXT)
            .header("If-Match", "0")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    mvc()
        .perform(patch(itemsPath() + "/" + ITEM_ID_TEXT)
            .header("If-Match", "0")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetGroupId\":\"abc\"}")
            .principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(items, never()).moveItem(anyLong(), anyLong(), anyLong(), anyLong(), anyInt());
  }

  // ---------- WAT-10 ----------

  @Test
  void wat10AcceptsStringIdsAndReturnsTheNewOrder() throws Exception {
    when(items.reorderItems(USER_ID, GROUP_ID, List.of(2L, 1L)))
        .thenReturn(List.of(item(2L, GROUP_ID, 0, 5), item(1L, GROUP_ID, 1, 1)));

    mvc()
        .perform(reorderRequest("[\"2\",\"1\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].itemId").value("2"))
        .andExpect(jsonPath("$.data[0].groupId").value(GROUP_ID_TEXT))
        .andExpect(jsonPath("$.data[0].sortNo").value(0))
        .andExpect(jsonPath("$.data[0].version").value(5))
        .andExpect(jsonPath("$.data[1].sortNo").value(1));
  }

  @Test
  void wat10RejectsANonNumericIdSet() throws Exception {
    mvc()
        .perform(reorderRequest("[\"abc\"]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(items, never()).reorderItems(anyLong(), anyLong(), any());
  }

  @Test
  void wat10MapsAStaleSetTo409() throws Exception {
    when(items.reorderItems(anyLong(), anyLong(), any()))
        .thenThrow(new WatchlistException(
            WatchlistErrorCode.VERSION_CONFLICT, "组内自选项已变化，请刷新后重试"));

    mvc()
        .perform(reorderRequest("[\"1\"]"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WATCHLIST_VERSION_CONFLICT"));
  }

  // ---------- 小工具 ----------

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new WatchlistItemController(items, guard, CLOCK))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .addFilters(new TraceIdFilter())
        .build();
  }

  private static String itemsPath() {
    return "/api/v1/watchlist-groups/" + GROUP_ID_TEXT + "/items";
  }

  private MockHttpServletRequestBuilder addRequest(String key, String securityId) {
    return post(itemsPath())
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"securityId\":\"" + securityId + "\"}")
        .principal(authentication());
  }

  private MockHttpServletRequestBuilder moveRequest(String ifMatch, long targetGroupId) {
    return patch(itemsPath() + "/" + ITEM_ID_TEXT)
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"targetGroupId\":\"" + targetGroupId + "\"}")
        .principal(authentication());
  }

  private MockHttpServletRequestBuilder reorderRequest(String itemIdsJson) {
    return put(itemsPath() + "/order")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"itemIds\":" + itemIdsJson + "}")
        .principal(authentication());
  }

  private static WatchlistEntry entry(boolean withQuote) {
    return entry(withQuote, summary(false));
  }

  private static WatchlistEntry entry(boolean withQuote, SecuritySummary summary) {
    return new WatchlistEntry(
        ITEM_ID, GROUP_ID, 600_519L, 0, 2, OffsetDateTime.now(CLOCK),
        summary, withQuote ? quote() : null, null);
  }

  private static WatchlistItem item(long groupId, int sortNo, int version) {
    return item(ITEM_ID, groupId, sortNo, version);
  }

  /** WAT-10 的排序响应必须回显请求里的 itemId，所以 id 要能单独指定。 */
  private static WatchlistItem item(long itemId, long groupId, int sortNo, int version) {
    return new WatchlistItem(
        itemId, USER_ID, groupId, 600_519L, sortNo, version,
        LocalDateTime.of(2026, 9, 20, 14, 30));
  }

  private static SecuritySummary summary(boolean suspended) {
    return new SecuritySummary(
        SECURITY_ID, "SH.600519", "600519", "模拟证券600519", "SH", "STOCK", "MAIN",
        suspended ? "SUSPENDED" : "LISTED", false, suspended, 2, null, null);
  }

  private static QuoteSnapshot quote() {
    return new QuoteSnapshot(
        summary(false), "10.00", "10.10", "10.50", "10.60", "9.90", "0.50", "0.05",
        "1000", "10500", "0.01",
        OffsetDateTime.parse("2026-09-20T07:00:00Z"),
        OffsetDateTime.parse("2026-09-20T07:00:05Z"),
        "sim-20260920", MarketOverview.DataStatus.REALTIME, 0);
  }

  private static UsernamePasswordAuthenticationToken authentication() {
    var principal = new AccessTokenPrincipal(
        USER_ID, "demo", Set.of("watchlist:read"), "jti-1", Instant.parse("2026-09-20T07:00:00Z"));
    return new UsernamePasswordAuthenticationToken(principal, "token", Set.of());
  }
}
