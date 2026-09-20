import { apiRequest, toQueryString } from './apiClient'
import type {
  CreatedWatchlistGroup,
  CreatedWatchlistItem,
  DeletedWatchlistGroup,
  DeletedWatchlistItem,
  MovedWatchlistItem,
  WatchlistGroup,
  WatchlistItemOrder,
  WatchlistOverview,
} from '@/types/domain'

/**
 * 自选中心接口（契约 §12.1 WAT-01~05、§12.2 WAT-06~12）。
 *
 * 只负责拼参 + `apiRequest`：不做筛选、不做格式化、不做本地排序。
 * 全部函数都要求调用方已登录——`apiClient` 里的 401 单飞刷新会处理过期的访问令牌，
 * 因此这里不重复处理认证失败。
 *
 * **`Idempotency-Key` 由调用方传入**，不在这里生成：键的语义是"一次用户意图"，
 * 而"重试时复用、换 body 时换新键"这条规则只有页面知道（见 `WatchlistPage.vue`）。
 * 在 service 里 `randomUUID()` 会让每次重试都变成一次新的写入意图。
 */

/** WAT-11：自选中心首屏聚合。`groupId` 缺省表示不过滤分组。 */
export function getWatchlistOverview(groupId?: string) {
  const search = toQueryString({ groupId })
  return apiRequest<WatchlistOverview>(`/watchlists/overview?${search}`)
}

/** WAT-02：新建分组。 */
export function createWatchlistGroup(groupName: string, idempotencyKey: string) {
  return apiRequest<CreatedWatchlistGroup>('/watchlist-groups', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ groupName }),
  })
}

/** WAT-03：重命名分组。`expectedVersion` 取响应里该组的 `version`。 */
export function renameWatchlistGroup(
  groupId: string,
  groupName: string,
  expectedVersion: number,
) {
  return apiRequest<WatchlistGroup>(`/watchlist-groups/${encodeURIComponent(groupId)}`, {
    method: 'PATCH',
    headers: { 'If-Match': String(expectedVersion) },
    body: JSON.stringify({ groupName }),
  })
}

/**
 * WAT-04：删除分组。
 *
 * 非空分组**必须**给出 `moveItemsToGroupId`，否则后端 400
 * `WATCHLIST_TARGET_GROUP_REQUIRED`——自选项不会随分组一起消失，这是契约刻意的要求。
 */
export function deleteWatchlistGroup(
  groupId: string,
  expectedVersion: number,
  moveItemsToGroupId?: string,
) {
  const search = toQueryString({ moveItemsToGroupId })
  return apiRequest<DeletedWatchlistGroup>(
    `/watchlist-groups/${encodeURIComponent(groupId)}?${search}`,
    {
      method: 'DELETE',
      headers: { 'If-Match': String(expectedVersion) },
    },
  )
}

/** WAT-05：分组重排。`groupIds` 必须是本人全部有效分组的排列，缺项 / 重复后端 400。 */
export function reorderWatchlistGroups(groupIds: string[]) {
  return apiRequest<WatchlistGroup[]>('/watchlist-groups/order', {
    method: 'PUT',
    body: JSON.stringify({ groupIds }),
  })
}

/** WAT-07：添加自选。同组同证券是**幂等成功**（返回已存在的那条），不是冲突。 */
export function addWatchlistItem(
  groupId: string,
  securityId: string,
  idempotencyKey: string,
) {
  return apiRequest<CreatedWatchlistItem>(
    `/watchlist-groups/${encodeURIComponent(groupId)}/items`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ securityId }),
    },
  )
}

/** WAT-08：移出自选。重复删除是幂等成功（但响应的 `deleted` 为 `false`）。 */
export function removeWatchlistItem(groupId: string, itemId: string) {
  return apiRequest<DeletedWatchlistItem>(
    `/watchlist-groups/${encodeURIComponent(groupId)}/items/${encodeURIComponent(itemId)}`,
    { method: 'DELETE' },
  )
}

/**
 * WAT-09：把自选项移动到本人其他分组。
 *
 * 目标组已有同证券时服务端会**合并**：源行被删除，响应返回目标组那一行且 `merged=true`。
 */
export function moveWatchlistItem(
  groupId: string,
  itemId: string,
  targetGroupId: string,
  expectedVersion: number,
) {
  return apiRequest<MovedWatchlistItem>(
    `/watchlist-groups/${encodeURIComponent(groupId)}/items/${encodeURIComponent(itemId)}`,
    {
      method: 'PATCH',
      headers: { 'If-Match': String(expectedVersion) },
      body: JSON.stringify({ targetGroupId }),
    },
  )
}

/**
 * WAT-10：组内重排。`itemIds` 必须是**组内全部当前项**的排列，
 * 集合不匹配时后端 409（要求调用方刷新后重试）。
 */
export function reorderWatchlistItems(groupId: string, itemIds: string[]) {
  return apiRequest<WatchlistItemOrder[]>(
    `/watchlist-groups/${encodeURIComponent(groupId)}/items/order`,
    {
      method: 'PUT',
      body: JSON.stringify({ itemIds }),
    },
  )
}
